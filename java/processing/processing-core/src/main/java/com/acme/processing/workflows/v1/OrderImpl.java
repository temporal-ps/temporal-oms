package com.acme.processing.workflows.v1;

import com.acme.oms.services.CommerceAppService;
import com.acme.oms.services.ProductInformationManagementService;
import com.acme.processing.workflows.Order;
import com.acme.processing.workflows.activities.Fulfillments;
import com.acme.processing.workflows.activities.Options;
import com.acme.processing.workflows.activities.Support;
import com.acme.proto.acme.processing.domain.processing.v1.*;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.workflow.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Baseline processing.Order workflow. No Worker Versioning. Always publishes the
 * Kafka fulfillment handoff.
 */
public class OrderImpl implements Order {
    private final Options optionsActs;
    private final Fulfillments fulfillments;
    private GetProcessOrderStateResponse state;
    private Logger logger = LoggerFactory.getLogger(OrderImpl.class);

    @WorkflowInit
    public OrderImpl(ProcessOrderRequest args) {
        Workflow.setCurrentDetails("Implementation type: `" + this.getClass().getName() + "`");

        this.state = GetProcessOrderStateResponse.newBuilder().build();
        this.state = this.state.toBuilder().addArgs(args).build();
        this.optionsActs = Workflow.newLocalActivityStub(Options.class,
                LocalActivityOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(2))
                        .build());
        this.fulfillments = Workflow.newActivityStub(Fulfillments.class,
                ActivityOptions.newBuilder()
                        .setSummary("/admin/order-fulfillment/" + args.getOrderId() + "")
                        .setScheduleToCloseTimeout(Duration.ofSeconds(60)).build());
    }

    @Override
    public GetProcessOrderStateResponse execute(ProcessOrderRequest request) {
        logger.info("Processing order {}", request);
        var opts = request.hasOptions()
                ? request.getOptions()
                : ProcessOrderRequestExecutionOptions.getDefaultInstance();
        if (!opts.hasOmsProperties()) {
            opts = this.optionsActs.getOptions(opts);
        }

        var integrationsEndpoint = opts.getOmsProperties().getProcessing().getNexus().getEndpointsOrThrow("integrations");
        final long timeoutSecs = opts.getProcessingTimeoutSecs() > 0 ? opts.getProcessingTimeoutSecs() : 86400L;

        var commerceAppService = Workflow.newNexusServiceStub(CommerceAppService.class,
                NexusServiceOptions.newBuilder()
                        .setEndpoint(integrationsEndpoint)
                        .setOperationOptions(NexusOperationOptions.newBuilder()
                                .setScheduleToCloseTimeout(Duration.ofSeconds(timeoutSecs))
                                .setCancellationType(NexusOperationCancellationType.WAIT_REQUESTED)
                                .build())
                        .build());

        var pimService = Workflow.newNexusServiceStub(ProductInformationManagementService.class,
                NexusServiceOptions.newBuilder()
                        .setEndpoint(integrationsEndpoint)
                        .setOperationOptions(NexusOperationOptions.newBuilder()
                                .setScheduleToCloseTimeout(Duration.ofSeconds(60))
                                .setCancellationType(NexusOperationCancellationType.WAIT_REQUESTED)
                                .build())
                        .build());



        // 1. validate order (immediate or manual correction via support)
        // 2. enrich order
        // 3. fulfill order
        // if any of this fails, cancel order
        var scope = Workflow.newCancellationScope(inner -> {

            var validation = commerceAppService.validateOrder(ValidateOrderRequest.newBuilder()
                    .setOrder(request.getOrder())
                    .setCustomerId(request.getCustomerId())
                    .setValidationTimeoutSecs(timeoutSecs).build());
            this.state = this.state.toBuilder().setValidation(validation).build();

            if (validation.getManualCorrectionNeeded() || validation.getValidationFailuresCount() > 0) {
                var support = Workflow.newActivityStub(Support.class, ActivityOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(timeoutSecs))
                        .setTaskQueue("support")
                        .build());
                this.state = this.state.toBuilder().setValidation(support.manuallyValidateOrder(ManuallyValidateOrderRequest.newBuilder()
                        .setOrder(request.getOrder())
                        .setCustomerId(request.getCustomerId())
                        .setWorkflowId(Workflow.getInfo().getWorkflowId())
                        .build())).build();
                if (this.state.getValidation().getValidationFailuresCount() > 0) {
                    return;
                }
            }

            this.state = this.state.toBuilder().setEnrichment(
                    pimService.enrichOrder(EnrichOrderRequest.newBuilder()
                            .setOrder(request.getOrder()).build())).build();

            try {
                this.state = this.state.toBuilder().setFulfillment(this.fulfillments.fulfillOrder(FulfillOrderRequest.newBuilder()
                        .setOrder(request.getOrder()).addAllItems(this.state.getEnrichment().getItemsList()).build())).build();
            } catch (ApplicationFailure e) {
                if (e.isNonRetryable()) {
                    // permanent failure
                    // move this to the support workflow
                }
            }

        });

        Workflow.newTimer(Duration.ofSeconds(timeoutSecs)).thenApply(result -> {
            if (!state.hasFulfillment()) {
                scope.cancel();
            }
            return null;
        });

        try {
            scope.run();
        } catch (ActivityFailure e) {
            this.state.toBuilder().addErrors(e.getMessage());
            throw e;
        }
        Workflow.await(Workflow::isEveryHandlerFinished);
        return this.state;
    }

    @Override
    public GetProcessOrderStateResponse getState() {
        return this.state;
    }
}
