package com.acme.enablements.commerce.workflows;

import com.acme.enablements.activities.CommerceInventoryActivities;
import com.acme.enablements.activities.PendingPublishRegistryActivities;
import com.acme.proto.acme.apps.api.orders.v1.Order;
import com.acme.proto.acme.apps.api.orders.v1.SelectedShipment;
import com.acme.proto.acme.apps.api.orders.v1.ShippingAddress;
import com.acme.proto.acme.apps.api.orders.v1.SubmitOrderRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceOrderState;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateCommerceOrderRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.HoldInventoryRequest;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;

import java.time.Duration;

public class CommerceOrderImpl implements CommerceOrder {

    private final CommerceInventoryActivities inventory = Workflow.newActivityStub(
            CommerceInventoryActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    private final PendingPublishRegistryActivities registry = Workflow.newActivityStub(
            PendingPublishRegistryActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    private CommerceOrderState state;

    @WorkflowInit
    public CommerceOrderImpl(CreateCommerceOrderRequest request) {
        var builder = CommerceOrderState.newBuilder()
                .setOrderId(Workflow.getInfo().getWorkflowId())
                .setCustomerId(request.getCustomerId())
                .addAllItems(request.getItemsList())
                .setShippingAddress(request.getShippingAddress())
                .setStatus("PLACED")
                .setPlacedAt(nowTimestamp())
                .setScenarioOptions(request.getScenarioOptions());
        if (request.hasSelectedShipment()) {
            builder.setSelectedShipment(request.getSelectedShipment());
        }
        this.state = builder.build();
    }

    @Override
    public void execute(CreateCommerceOrderRequest request) {
        inventory.hold(HoldInventoryRequest.newBuilder()
                .setOrderId(state.getOrderId())
                .addAllItems(state.getItemsList())
                .build());

        registry.registerCommerceOrder(state.getOrderId(), state.getScenarioOptions().getScenario(), toSubmitOrderRequestJson());
    }

    @Override
    public CommerceOrderState getState() {
        return state;
    }

    private String toSubmitOrderRequestJson() {
        var orderBuilder = Order.newBuilder()
                .setOrderId(state.getOrderId())
                .addAllItems(state.getItemsList().stream()
                        .map(item -> com.acme.proto.acme.apps.api.orders.v1.Item.newBuilder()
                                .setItemId(item.getItemId())
                                .setQuantity(item.getQuantity())
                                .build())
                        .toList());

        if (state.getShippingAddress().hasEasypost()) {
            var ep = state.getShippingAddress().getEasypost();
            orderBuilder.setShippingAddress(ShippingAddress.newBuilder()
                    .setStreet(ep.getStreet1())
                    .setCity(ep.getCity())
                    .setState(ep.getState())
                    .setPostalCode(ep.getZip())
                    .setCountry(ep.getCountry())
                    .build());
        }

        if (state.hasSelectedShipment()) {
            var shipment = state.getSelectedShipment();
            var selectedBuilder = SelectedShipment.newBuilder();
            if (shipment.hasPaidPrice()) {
                selectedBuilder.setPaidPriceCents(shipment.getPaidPrice().getUnits())
                        .setCurrency(shipment.getPaidPrice().getCurrency());
            }
            if (shipment.hasEasypost() && shipment.getEasypost().hasSelectedRate()) {
                var rate = shipment.getEasypost().getSelectedRate();
                selectedBuilder.setRateId(rate.getRateId());
                if (rate.hasDeliveryDays()) {
                    selectedBuilder.setDeliveryDays((int) rate.getDeliveryDays());
                }
            }
            orderBuilder.setSelectedShipment(selectedBuilder.build());
        }

        var request = SubmitOrderRequest.newBuilder()
                .setCustomerId(state.getCustomerId())
                .setOrder(orderBuilder.build())
                .build();
        try {
            return JsonFormat.printer().print(request);
        } catch (Exception e) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Failed to serialize SubmitOrderRequest for order " + state.getOrderId(), "SerializationFailure");
        }
    }

    private static Timestamp nowTimestamp() {
        long millis = Workflow.currentTimeMillis();
        return Timestamp.newBuilder()
                .setSeconds(millis / 1000)
                .setNanos((int) ((millis % 1000) * 1_000_000))
                .build();
    }
}
