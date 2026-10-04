package com.acme.enablements.commerce.workflows;

import com.acme.enablements.activities.CommerceInventoryActivities;
import com.acme.enablements.activities.PendingPublishRegistryActivities;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceOrderEvent;
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

        registry.registerCommerceOrder(state.getOrderId(), state.getScenarioOptions().getScenario(), toOrderSubmittedEventJson());
    }

    @Override
    public CommerceOrderState getState() {
        return state;
    }

    private String toOrderSubmittedEventJson() {
        var event = CommerceOrderEvent.newBuilder()
                .setEventId(Workflow.randomUUID().toString())
                .setType("commerce.order.submitted")
                .setCreated(nowTimestamp())
                .setOrder(state)
                .build();
        try {
            return JsonFormat.printer().print(event);
        } catch (Exception e) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "Failed to serialize CommerceOrderEvent for order " + state.getOrderId(), "SerializationFailure");
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
