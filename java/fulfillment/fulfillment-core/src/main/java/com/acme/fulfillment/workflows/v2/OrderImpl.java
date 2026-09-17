package com.acme.fulfillment.workflows.v2;

import com.acme.fulfillment.workflows.Order;
import com.acme.fulfillment.workflows.activities.Carriers;
import com.acme.fulfillment.workflows.activities.FulfillmentOptionsLoader;
import com.acme.oms.services.InventoryService;
import com.acme.oms.services.ShippingAgent;
import com.acme.proto.acme.common.v1.Money;
import com.acme.proto.acme.common.v1.Shipment;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.*;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.common.SearchAttributeKey;
import io.temporal.common.VersioningBehavior;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * fulfillment.Order Workflow Implementation (V2)
 *
 * Lifecycle:
 *  1. [UpdateWithStart] validateOrder → Carriers.verifyAddress → store verified Address → return
 *  2. execute() unblocks → loadOptions (LocalActivity) → InventoryService.holdItems → await fulfillOrder/cancel/timeout
 *  3. [Update] fulfillOrder → stores request → InventoryService.reserveItems → ShippingAgent.recommendShippingOption (Nexus)
 *               → apply recommendation → concurrent: Carriers.printShippingLabel + InventoryService.deductInventory → return response
 *  4. execute() awaits state.notifyDeliveryStatus → updates delivery_status + status → complete
 *  5. [Signal] notifyDeliveryStatus → stores NotifyDeliveryStatusRequest in state
 *
 * Worker Versioning: PINNED. Behavior is unchanged from v1; only the version identity
 * is new, required before fulfillment.Order can evolve safely on its own schedule.
 *
 * Compensation: detached scope releases inventory hold via InventoryService on cancelOrder Signal or timeout.
 */
public class OrderImpl implements Order {

    // margin_leak / sla_breach_days registered in fulfillment namespace as --type Int (maps to Long in Java SDK)
    private static final SearchAttributeKey<Long> MARGIN_LEAK     = SearchAttributeKey.forLong("margin_leak");
    private static final SearchAttributeKey<Long> SLA_BREACH_DAYS = SearchAttributeKey.forLong("sla_breach_days");
    private static final long DEFAULT_FULFILLMENT_TIMEOUT_SECS = 86_400L; // 24 hours

    private final Carriers carriers;
    private final FulfillmentOptionsLoader optionsLoader;

    // Initialized in execute() after FulfillmentOptions are loaded (endpoint names needed)
    private InventoryService inventory;
    private ShippingAgent shippingAgent;

    private GetFulfillmentOrderStateResponse state;
    private boolean cancelFlag = false;
    private boolean fulfillOrderReceived = false;

    private final Logger logger = LoggerFactory.getLogger(OrderImpl.class);

    @WorkflowInit
    public OrderImpl(StartOrderFulfillmentRequest args) {
        Workflow.setCurrentDetails("Implementation type: `" + this.getClass().getName() + "`");

        this.state = GetFulfillmentOrderStateResponse.newBuilder()
                .setArgs(args)
                .setStatus(FulfillmentStatus.FULFILLMENT_STATUS_STARTED)
                .build();

        this.carriers = Workflow.newActivityStub(Carriers.class,
                ActivityOptions.newBuilder()
                        .setTaskQueue("fulfillment-carriers")
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        .build());
        this.optionsLoader = Workflow.newLocalActivityStub(FulfillmentOptionsLoader.class,
                LocalActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(5))
                        .build());
    }

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public void execute(StartOrderFulfillmentRequest request) {
        logger.info("fulfillment.Order started for order_id={}", request.getOrderId());

        // Wait for validateOrder Update (delivered as part of UpdateWithStart from apps.Order)
        Workflow.await(() -> state.hasValidatedAddress() || cancelFlag);
        if (cancelFlag) {
            logger.info("Order {} cancelled before address validation", request.getOrderId());
            return;
        }

        // Load fulfillment policy (shipping_margin + endpoint names) via LocalActivity
        var options = optionsLoader.loadOptions(
                LoadFulfillmentOptionsRequest.newBuilder()
                        .setOrderId(request.getOrderId())
                        .build());
        this.state = this.state.toBuilder().setOptions(options).build();

        // Configure InventoryService Nexus stub using the integrations endpoint from options
        this.inventory = Workflow.newNexusServiceStub(InventoryService.class,
                NexusServiceOptions.newBuilder()
                        .setEndpoint(options.getIntegrationsEndpoint())
                        .setOperationOptions(NexusOperationOptions.newBuilder()
                                .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                                .build())
                        .build());

        // Configure ShippingAgent Nexus stub — routes to Python fulfillment namespace, "agents" task queue
        this.shippingAgent = Workflow.newNexusServiceStub(ShippingAgent.class,
                NexusServiceOptions.newBuilder()
                        .setEndpoint(options.getShippingAgentEndpoint())
                        .setOperationOptions(NexusOperationOptions.newBuilder()
                                .setScheduleToCloseTimeout(Duration.ofSeconds(120))
                                .build())
                        .build());

        // Eagerly hold inventory while apps.Order processes the order
        var holdResponse = inventory.holdItems(
                HoldItemsRequest.newBuilder()
                        .setOrderId(request.getOrderId())
                        .addAllItems(extractStartItems(request))
                        .build());

        this.state = this.state.toBuilder()
                .setStatus(FulfillmentStatus.FULFILLMENT_STATUS_VALIDATED)
                .build();

        // Detached scope: releaseHold runs even if the workflow scope is cancelled
        var releaseHoldScope = Workflow.newDetachedCancellationScope(() ->
                inventory.releaseHold(ReleaseHoldRequest.newBuilder()
                        .setOrderId(request.getOrderId())
                        .setHoldId(holdResponse.getHoldId())
                        .build()));

        // Wait for fulfillOrder Update, cancelOrder Signal, or timeout
        long timeoutSecs = (request.hasOptions() && request.getOptions().getFulfillmentTimeoutSecs() > 0)
                ? request.getOptions().getFulfillmentTimeoutSecs()
                : DEFAULT_FULFILLMENT_TIMEOUT_SECS;

        boolean conditionMet = Workflow.await(Duration.ofSeconds(timeoutSecs),
                () -> fulfillOrderReceived || cancelFlag);

        if (!conditionMet || cancelFlag) {
            logger.info("Order {} did not receive fulfillOrder in time or was cancelled — releasing hold",
                    request.getOrderId());
            this.state = this.state.toBuilder()
                    .setStatus(FulfillmentStatus.FULFILLMENT_STATUS_CANCELED)
                    .build();
            releaseHoldScope.run();
            return;
        }

        // Await delivery status — set by notifyDeliveryStatus signal or pre-populated via delivery_status_request
        Workflow.await(() -> state.hasNotifyDeliveryStatus());

        var notification = state.getNotifyDeliveryStatus();
        this.state = this.state.toBuilder()
                .setDeliveryStatus(notification.getDeliveryStatus())
                .setStatus(notification.getDeliveryStatus() == DeliveryStatus.DELIVERY_STATUS_DELIVERED
                        ? FulfillmentStatus.FULFILLMENT_STATUS_DELIVERED
                        : FulfillmentStatus.FULFILLMENT_STATUS_CANCELED)
                .build();

        logger.info("fulfillment.Order complete for order_id={}, status={}",
                request.getOrderId(), state.getStatus());
    }

    // ── validateOrder Update ──────────────────────────────────────────────────

    @Override
    public void validateValidateOrder(ValidateOrderRequest request) {
        if (request.getOrderId().isBlank()) {
            throw new IllegalArgumentException("order_id is required for validateOrder");
        }
        if (!request.hasAddress()) {
            throw new IllegalArgumentException("address is required for validateOrder");
        }
    }

    @Override
    public ValidateOrderResponse validateOrder(ValidateOrderRequest request) {
        logger.info("Verifying address for order_id={}", request.getOrderId());

        var verifyResponse = carriers.verifyAddress(
                VerifyAddressRequest.newBuilder()
                        .setAddress(request.getAddress())
                        .setCustomerId(state.getArgs().getCustomerId())
                        .build());

        this.state = this.state.toBuilder()
                .setValidatedAddress(verifyResponse.getAddress())
                .build();

        return ValidateOrderResponse.newBuilder()
                .setAddress(verifyResponse.getAddress())
                .build();
    }

    // ── fulfillOrder Update ───────────────────────────────────────────────────

    @Override
    public void validateFulfillOrder(FulfillOrderRequest request) {
        if (!state.hasValidatedAddress()) {
            throw ApplicationFailure.newNonRetryableFailure(
                    "validateOrder must complete before fulfillOrder can be accepted",
                    "PRECONDITION_FAILED");
        }
        if (!request.hasProcessedOrder()) {
            throw new IllegalArgumentException("processed_order is required for fulfillOrder");
        }
    }

    @Override
    public FulfillOrderResponse fulfillOrder(FulfillOrderRequest request) {
        var orderId = request.getProcessedOrder().getOrderId();
        logger.info("Processing fulfillOrder for order_id={}", orderId);

        this.fulfillOrderReceived = true;
        this.state = this.state.toBuilder()
                .setFulfillmentRequest(request)
                .setStatus(FulfillmentStatus.FULFILLMENT_STATUS_FULFILLING)
                .build();

        // Reserve inventory with warehouse allocations from processing
        var reservation = inventory.reserveItems(
                ReserveItemsRequest.newBuilder()
                        .setOrderId(orderId)
                        .addAllItems(extractProcessedItems(request.getProcessedOrder()))
                        .build());

        Shipment selectedShipment = resolveSelectedShipment(request);

        // Get AI-driven shipping recommendation via ShippingAgent Nexus operation (UpdateWithStart)
        var shippingRequestBuilder = RecommendShippingOptionRequest.newBuilder()
                        .setOrderId(orderId)
                        .setCustomerId(request.getProcessedOrder().getCustomerId())
                        .setToAddress(state.getValidatedAddress())
                        .addAllItems(convertToShippingLineItems(request.getProcessedOrder()));

        if (hasSelectedShipmentData(selectedShipment)) {
            shippingRequestBuilder.setSelectedShipment(selectedShipment);
        }

        var shippingResponse = shippingAgent.recommendShippingOption(shippingRequestBuilder.build());

        // Apply recommendation: select rate and compute margin delta
        var selection = applyRecommendation(shippingResponse, state.getOptions().getShippingMargin());

        if (selection.getMarginDeltaCents() > 0) {
            Workflow.upsertTypedSearchAttributes(MARGIN_LEAK.valueSet(selection.getMarginDeltaCents()));
        }

        if (shippingResponse.getRecommendation().getOutcome() == RecommendationOutcome.SLA_BREACH
                && hasSelectedDeliveryDays(selectedShipment)) {
            int promisedDays = (int) selectedShipment.getEasypost().getSelectedRate().getDeliveryDays();
            int actualDays   = findOption(shippingResponse, selection.getOptionId()).getEstimatedDays();
            long breachDays  = Math.max(0L, (long)(actualDays - promisedDays));
            Workflow.upsertTypedSearchAttributes(SLA_BREACH_DAYS.valueSet(breachDays));
        }

        this.state = this.state.toBuilder().setShippingSelection(selection).build();

        // Retrieve the selected ShippingOption to get the EasyPost shipment_id for label printing
        var selectedOption = findOption(shippingResponse, selection.getOptionId());

        // Print label and deduct inventory concurrently — independent terminal operations
        var labelPromise = Async.function(carriers::printShippingLabel,
                PrintShippingLabelRequest.newBuilder()
                        .setOrderId(orderId)
                        .setShipmentId(selectedOption.getShipmentId())
                        .setRateId(selectedOption.getRateId())
                        .build());

        var deductPromise = Async.function(inventory::deductInventory,
                DeductInventoryRequest.newBuilder()
                        .setOrderId(orderId)
                        .setReservationId(reservation.getReservationId())
                        .build());

        Promise.allOf(labelPromise, deductPromise).get();

        var labelResponse = labelPromise.get();
        this.state = this.state.toBuilder()
                .setTrackingNumber(labelResponse.getTrackingNumber())
                .setStatus(FulfillmentStatus.FULFILLMENT_STATUS_COMPLETED)
                .build();

        logger.info("Label printed for order_id={}, tracking_number={}", orderId, labelResponse.getTrackingNumber());

        // Pre-populate notify_delivery_status from request so execute can proceed without signal
        if (request.hasDeliveryStatusRequest()) {
            this.state = this.state.toBuilder()
                    .setNotifyDeliveryStatus(request.getDeliveryStatusRequest())
                    .build();
        }

        return FulfillOrderResponse.newBuilder()
                .setTrackingNumber(labelResponse.getTrackingNumber())
                .setShippingSelection(selection)
                .build();
    }

    // ── cancelOrder Signal ────────────────────────────────────────────────────

    @Override
    public void cancelOrder(CancelFulfillmentOrderRequest request) {
        logger.info("cancelOrder received for order_id={}: {}", request.getOrderId(), request.getReason());
        this.cancelFlag = true;
    }

    // ── notifyDeliveryStatus Signal ───────────────────────────────────────────

    @Override
    public void notifyDeliveryStatus(NotifyDeliveryStatusRequest request) {
        logger.info("notifyDeliveryStatus for order_id={}: {}",
                request.getOrderId(), request.getDeliveryStatus());
        this.state = this.state.toBuilder()
                .setNotifyDeliveryStatus(request)
                .build();
    }

    // ── getState Query ────────────────────────────────────────────────────────

    @Override
    public GetFulfillmentOrderStateResponse getState() {
        return this.state;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ShippingOption findOption(RecommendShippingOptionResponse response, String optionId) {
        return response.getOptionsList().stream()
                .filter(o -> o.getId().equals(optionId))
                .findFirst()
                .orElseThrow(() -> ApplicationFailure.newNonRetryableFailure(
                        "Recommended option not found in ShippingAgent response: " + optionId, "NO_OPTION"));
    }

    private ShippingSelection applyRecommendation(RecommendShippingOptionResponse response, Money shippingMargin) {
        var rec    = response.getRecommendation();
        var option = findOption(response, rec.getRecommendedOptionId());
        long delta = Math.max(0L, option.getCost().getUnits() - shippingMargin.getUnits());
        boolean isFallback = rec.getOutcome() == RecommendationOutcome.MARGIN_SPIKE
                          || rec.getOutcome() == RecommendationOutcome.SLA_BREACH;
        var builder = ShippingSelection.newBuilder()
                .setOptionId(rec.getRecommendedOptionId())
                .setRateId(option.getRateId())
                .setCarrier(option.getCarrier())
                .setServiceLevel(option.getServiceLevel())
                .setActualPrice(option.getCost())
                .setMarginDeltaCents(delta)
                .setIsFallback(isFallback);
        if (isFallback) {
            builder.setFallbackReason(rec.getOutcome().name() + ": " + rec.getReasoning());
        }
        return builder.build();
    }

    private Shipment resolveSelectedShipment(FulfillOrderRequest request) {
        if (request.hasSelectedShipment()) {
            return request.getSelectedShipment();
        }
        return state.getArgs().getSelectedShipment();
    }

    private boolean hasSelectedDeliveryDays(Shipment selectedShipment) {
        return selectedShipment.hasEasypost()
                && selectedShipment.getEasypost().hasSelectedRate()
                && selectedShipment.getEasypost().getSelectedRate().hasDeliveryDays();
    }

    private boolean hasSelectedShipmentData(Shipment selectedShipment) {
        return selectedShipment.hasPaidPrice()
                || selectedShipment.hasEasypost()
                || selectedShipment.hasDeliveryDate();
    }

    private List<ShippingLineItem> convertToShippingLineItems(ProcessedOrder processedOrder) {
        return processedOrder.getItemsList().stream()
                .map(item -> ShippingLineItem.newBuilder()
                        .setSkuId(item.getSkuId())
                        .setQuantity(item.getQuantity())
                        .build())
                .toList();
    }

    private List<FulfillmentItem> extractStartItems(StartOrderFulfillmentRequest request) {
        return request.getPlacedOrder().getItemsList();
    }

    private List<FulfillmentItem> extractProcessedItems(ProcessedOrder processedOrder) {
        return processedOrder.getItemsList();
    }
}
