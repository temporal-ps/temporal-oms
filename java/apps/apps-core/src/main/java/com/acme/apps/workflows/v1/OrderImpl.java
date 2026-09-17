package com.acme.apps.workflows.v1;

import com.acme.apps.workflows.Order;
import com.acme.apps.workflows.activities.Options;
import com.acme.oms.services.Processing;
import com.acme.proto.acme.apps.domain.apps.v1.*;
import com.acme.proto.acme.apps.domain.apps.v1.Errors;
import com.acme.proto.acme.processing.domain.processing.v1.*;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Baseline CompleteOrder workflow. No Worker Versioning. Delegates to processing.Order
 * only; has no awareness of fulfillment.Order.
 */
public class OrderImpl implements Order {
    private final Options optionsActs;
    private GetCompleteOrderStateResponse state;
    private final Logger logger = LoggerFactory.getLogger(OrderImpl.class);
    private Processing processing;

    @WorkflowInit
    public OrderImpl(CompleteOrderRequest args) {
        Workflow.setCurrentDetails("Implementation type: `" + this.getClass().getName() + "`");

        this.state = GetCompleteOrderStateResponse.newBuilder()
                .setArgs(args)
                .setOptions(args.getOptions())
                        .build();
        if(args.hasProcessOrder()) {
            this.state = this.state.toBuilder()
                    .setProcessOrder(args.getProcessOrder()).build();
        }

        this.optionsActs = Workflow.newLocalActivityStub(Options.class, LocalActivityOptions.newBuilder().
                setScheduleToCloseTimeout(Duration.ofSeconds(2)).build());

    }

    @Override
    public void execute(CompleteOrderRequest request) {
        logger.info("Starting CompleteOrder Workflow {} - {}", request, state.hasOptions());

        if(!request.hasOptions()) {
            // get once the options while allowing environment or other config to be applied/merged from input request
            CompleteOrderRequestExecutionOptions options = this.optionsActs.getOptions(
                    GetOptionsRequest.newBuilder()
                            .setOptions(request.getOptions())
                            .setTimestamp(request.getTimestamp())
                            .build());
            this.state = this.state.toBuilder()
                    .setOptions(options)
                    .build();
        }

        var elapsed = getElapsedTime();
        var remainingTime = Math.max(0,this.state.getOptions().getCompletionTimeoutSecs() - elapsed.getSeconds());
        var processingEndpoint = this.state.getOptions()
                .getOmsProperties()
                .getApps()
                .getNexus()
                .getEndpointsOrThrow("order-processing");

        this.processing = Workflow.newNexusServiceStub(Processing.class,
                NexusServiceOptions.newBuilder()
                        .setOperationOptions(NexusOperationOptions.newBuilder()
                                .setScheduleToCloseTimeout(Duration.ofSeconds(remainingTime))
                                .setCancellationType(NexusOperationCancellationType.WAIT_REQUESTED)
                                .build())
                        .setEndpoint(processingEndpoint)
                        .build());

        // wait for processing timeout to fire OR
        // wait until any processOrder operation has been completed or needs to be cancelled and reexecuted
        // Since this is the top-level ApplicationService, we are accumulating inputs before forwarding them to the DomainService.

        var conditionIsMet = Workflow.await(Duration.ofSeconds(remainingTime), ()->
                this.state.hasProcessOrder() || this.state.hasCancellation());
        if(!conditionIsMet) {
            // we didnt get a processable order in time
            // note that we are not treating this as a Failure, just a business condition
            return;
        }
        // check for cancellation first
        if(this.state.hasCancellation()) {
            this.compensateOrder();
            return;
        }

        CancellationScope scope = Workflow.newCancellationScope(() -> {
            var processedOrder = this.processing.processOrder(this.state.getProcessOrder());
            this.state = this.state.toBuilder().setProcessedOrder(processedOrder).build();
        });
        Workflow.newTimer(Duration.ofSeconds(remainingTime)).thenApply(result -> {
            if(!this.state.hasProcessedOrder()) {
                scope.cancel();
            }
            return null;
        });

        try {
            scope.run();
        } catch (NexusOperationFailure e) {
            // the Operation failed so we will failed this workflow also
            // Alternatively, we could simply return and inspect errors later to take a separate action
            this.state = this.state.toBuilder().addErrors(e.getMessage()).build();
            if(e.getCause() instanceof CanceledFailure) {
                return;
            }
            throw e;
        }

        if(this.state.getErrorsCount() == 0) {
            Workflow.await(Workflow::isEveryHandlerFinished);
            return;
        }

        // the order was not processed successfully
        this.compensateOrder();
    }

    private void compensateOrder() {
        // use a detached scope to step outside the parent scope and perform cleanup operations (even activities)
        var detached = Workflow.newDetachedCancellationScope(()-> {
            if(this.state.getCapturedPaymentsCount() > 0) {
                // void or refund payment
            }
            if (this.state.getSubmittedOrdersCount() > 0) {
                // notify customer of inability to complete order
            }

        });
        detached.run();
    }

    @Override
    public void validateSubmitOrder(SubmitOrderRequest request) {
        if(this.state.hasProcessOrder()) {
            // ApplicationFailure will not cause a rescheduled Workflow Task
            throw ApplicationFailure.newFailure(Errors.ERRORS_CONFLICT.name(), "Order processing already in progress");
        }
        if(!request.hasOrder()) {
            logger.warn("Order is required");
            // this does not require ApplicationFailure to decline update acceptance
            throw new IllegalArgumentException("Order is required");
        }
        if(!request.getOrder().getOrderId().equals(this.state.getArgs().getOrderId())) {
            logger.warn("Order ID does not match");
            throw new IllegalArgumentException("Order ID does not match");
        }
    }
    @Override
    public GetCompleteOrderStateResponse submitOrder(SubmitOrderRequest request) {
        logger.info("Received order {}", request);
        // Add new request and sort all submitted orders by timestamp (descending - newest first)
        List<SubmitOrderRequest> orders = new ArrayList<>(state.getSubmittedOrdersList());
        orders.add(request);
        orders.sort((a, b) -> Long.compare(b.getTimestamp().getSeconds(), a.getTimestamp().getSeconds()));

        // Rebuild state with sorted orders
        GetCompleteOrderStateResponse.Builder stateBuilder = state.toBuilder().clearSubmittedOrders();
        for (SubmitOrderRequest order : orders) {
            stateBuilder.addSubmittedOrders(order);
        }
        this.state = stateBuilder.build();
        this.tryScheduleProcessOrder();
        return this.state;
    }


    @Override
    public void validateCapturePayment(CapturePaymentRequest request) {
        if(this.state.hasProcessOrder()) {
            // ApplicationFailure will not cause a rescheduled Workflow Task
            throw ApplicationFailure.newFailure("Order processing already in progress", Errors.ERRORS_CONFLICT.name());
        }
        if(!request.getPayment().hasAmount()){
            throw ApplicationFailure.newFailure("Payment RRN is required", Errors.ERRORS_INVALID_ARGUMENTS.name());
        }
    }
    @Override
    public GetCompleteOrderStateResponse capturePayment(CapturePaymentRequest request) {
        // Add new request and sort all captured payments by timestamp (descending - newest first)
        List<CapturePaymentRequest> payments = new ArrayList<>(state.getCapturedPaymentsList());
        payments.add(request);
        payments.sort((a, b) -> Long.compare(b.getTimestamp().getSeconds(), a.getTimestamp().getSeconds()));

        // Rebuild state with sorted payments
        GetCompleteOrderStateResponse.Builder stateBuilder = state.toBuilder().clearCapturedPayments();
        for (CapturePaymentRequest payment : payments) {
            stateBuilder.addCapturedPayments(payment);
        }
        this.state = stateBuilder.build();
        this.tryScheduleProcessOrder();
        return this.state;
    }

    private void tryScheduleProcessOrder() {
        if (state.getSubmittedOrdersCount() > 0 && state.getCapturedPaymentsCount() > 0) {
            var order = state.getSubmittedOrders(0);
            var payment = state.getCapturedPayments(0);
            var ts = order.getTimestamp().getSeconds() > payment.getTimestamp().getSeconds() ? order.getTimestamp() : payment.getTimestamp();

            this.state = this.state.toBuilder().setProcessOrder(ProcessOrderRequest.newBuilder()
                    .setOrderId(this.state.getArgs().getOrderId())
                    .setCustomerId(this.state.getArgs().getCustomerId())
                    .setOrder(state.getSubmittedOrders(0).getOrder())
                    .setPayment(state.getCapturedPayments(0).getPayment())
                    .setTimestamp(ts)
                    .setOptions(ProcessOrderRequestExecutionOptions.newBuilder()
                            .setProcessingTimeoutSecs(
                                    state.getOptions().getProcessingTimeoutSecs())
                            .build())).build();
        }
    }

    @Override
    public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
        this.state = this.state.toBuilder().setCancellation(request).build();
        return CancelOrderResponse.getDefaultInstance();
    }

    @Override
    public void validateCancelOrder(CancelOrderRequest request) {
        if(this.state.hasProcessOrder()) {
            throw ApplicationFailure.newFailure("Order processing already in progress", Errors.ERRORS_CONFLICT.name());
        }
    }

    @Override
    public GetCompleteOrderStateResponse getState() {
        return this.state;
    }

    private Duration getElapsedTime() {
        long startMillis = this.state.getArgs().getTimestamp().getSeconds() * 1000
            + this.state.getArgs().getTimestamp().getNanos() / 1_000_000;
        long elapsedMillis = Workflow.currentTimeMillis() - startMillis;
        return Duration.ofMillis(elapsedMillis);
    }

}
