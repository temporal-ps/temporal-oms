package com.acme.fulfillment.services;

import com.acme.fulfillment.workflows.Order;
import com.acme.oms.services.Fulfillment;
import com.acme.proto.acme.fulfillment.domain.fulfillment.v1.*;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.TemporalOperationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("fulfillment-service")
@ServiceImpl(service = Fulfillment.class)
public class FulfillmentImpl {

    private final Logger logger = LoggerFactory.getLogger(FulfillmentImpl.class);

    @OperationImpl
    public OperationHandler<StartOrderFulfillmentRequest, ValidateOrderResponse> validateOrder() {
        // Synchronous operation: UpdateWithStart on fulfillment.Order.
        // The validateOrder Update must complete within Nexus's 10-second sync limit.
        // If latency becomes a concern, switch to an async WorkflowRunOperation pattern.
        return OperationHandler.sync((ctx, details, request) -> {
            logger.info("validateOrder Nexus operation for order_id={}", request.getOrderId());

            WorkflowClient client = Nexus.getOperationContext().getWorkflowClient();

            WorkflowOptions wfOptions = WorkflowOptions.newBuilder()
                    .setWorkflowId(request.getOrderId())
                    .setTaskQueue("fulfillment")
                    .setWorkflowIdConflictPolicy(
                            WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                    .setWorkflowIdReusePolicy(
                            WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                    .build();

            Order orderWorkflow = client.newWorkflowStub(Order.class, wfOptions);

            ValidateOrderRequest validateRequest = ValidateOrderRequest.newBuilder()
                    .setOrderId(request.getOrderId())
                    .setAddress(toCommonAddress(request))
                    .build();

            return WorkflowClient.executeUpdateWithStart(
                    orderWorkflow::validateOrder,
                    validateRequest,
                    UpdateOptions.<ValidateOrderResponse>newBuilder()
                            .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                            .setUpdateName(details.getRequestId())
                            .build(),
                    new WithStartWorkflowOperation<>(orderWorkflow::execute, request));
        });
    }

    // Dispatch fulfillOrder Update to the running fulfillment.Order workflow.
    // We wait only for ACCEPTED — fulfillOrder awaits delivery status (long-running)
    // and apps.Order does not need the result; fulfillment.Order is the source of truth.
    @OperationImpl
    public OperationHandler<FulfillOrderRequest, FulfillOrderResponse> fulfillOrder() {
        return TemporalOperationHandler.create(
                (context, client, input) -> {
                    var orderId = input.getProcessedOrder().getOrderId();
                    logger.info("fulfillOrder Nexus operation for order_id={}", orderId);
                    UpdateOptions.Builder<FulfillOrderResponse> optionsBuilder =
                            UpdateOptions.newBuilder(FulfillOrderResponse.class)
                                    .setUpdateName("fulfillOrder")
                                    .setWaitForStage(WorkflowUpdateStage.ACCEPTED);
                    // TODO support this for idempotency
//                    if (input.getUpdateId() != null) {
//                        optionsBuilder.setUpdateId(input.getUpdateId());
//                    }
                    return client.startWorkflowUpdate(Order.class, orderId, Order::fulfillOrder, input, optionsBuilder.build());
                });
    }

    private com.acme.proto.acme.common.v1.Address toCommonAddress(StartOrderFulfillmentRequest request) {
        return request.getPlacedOrder().getShippingAddress();
    }
}
