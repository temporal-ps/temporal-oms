package com.acme.apps.services;

import com.acme.apps.workflows.Order;
import com.acme.oms.services.AppsService;
import com.acme.proto.acme.apps.domain.apps.v1.CapturePaymentRequest;
import com.acme.proto.acme.apps.domain.apps.v1.GetCompleteOrderStateResponse;
import com.acme.proto.acme.apps.domain.apps.v1.SubmitOrderRequest;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.*;
import io.temporal.nexus.TemporalOperationHandler;
import io.temporal.nexus.TemporalOperationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("apps-service")
@ServiceImpl(service = AppsService.class)
public class AppsServiceImpl {
    private Logger logger = LoggerFactory.getLogger(AppsServiceImpl.class);

    /**
     * Starts the Order workflow with UpdateWithStart when the request carries a
     * complete_order_request; otherwise sends the update to the existing Order workflow.
     */
    @OperationImpl
    public OperationHandler<CapturePaymentRequest, GetCompleteOrderStateResponse> capturePayment() {
        return TemporalOperationHandler.create((context, client, request) -> {
            var orderId = request.getOrderId();
            if (request.hasCompleteOrderRequest()) {
                var workflow = client.getWorkflowClient().newWorkflowStub(Order.class, startOptions(orderId));
                // Synchronous UpdateWithStart: the update must complete within the Nexus sync deadline.
                return TemporalOperationResult.sync(WorkflowClient.executeUpdateWithStart(
                        workflow::capturePayment,
                        request,
                        UpdateOptions.<GetCompleteOrderStateResponse>newBuilder()
                                .setUpdateId(context.getRequestId())
                                .build(),
                        new WithStartWorkflowOperation<>(workflow::execute, request.getCompleteOrderRequest())));
            }
            return client.startWorkflowUpdate(
                    Order.class,
                    orderId,
                    Order::capturePayment,
                    request,
                    UpdateOptions.newBuilder(GetCompleteOrderStateResponse.class)
                            .setUpdateName("capturePayment")
                            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                            .build());
        });
    }

    /**
     * Starts the Order workflow with UpdateWithStart when the request carries a
     * complete_order_request; otherwise sends the update to the existing Order workflow.
     */
    @OperationImpl
    public OperationHandler<SubmitOrderRequest, GetCompleteOrderStateResponse> submitOrder() {
        return TemporalOperationHandler.create((context, client, request) -> {
            var orderId = request.getOrderId();
            if (request.hasCompleteOrderRequest()) {
                var workflow = client.getWorkflowClient().newWorkflowStub(Order.class, startOptions(orderId));
                // Synchronous UpdateWithStart: the update must complete within the Nexus sync deadline.
                return TemporalOperationResult.sync(WorkflowClient.executeUpdateWithStart(
                        workflow::submitOrder,
                        request,
                        UpdateOptions.<GetCompleteOrderStateResponse>newBuilder()
                                .setUpdateId(context.getRequestId())
                                .build(),
                        new WithStartWorkflowOperation<>(workflow::execute, request.getCompleteOrderRequest())));
            }
            return client.startWorkflowUpdate(
                    Order.class,
                    orderId,
                    Order::submitOrder,
                    request,
                    UpdateOptions.newBuilder(GetCompleteOrderStateResponse.class)
                            .setUpdateName("submitOrder")
                            .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                            .build());
        });
    }

    private static WorkflowOptions startOptions(String orderId) {
        return WorkflowOptions.newBuilder()
                .setWorkflowId(orderId)
                .setTaskQueue("apps")
                // USE_EXISTING lets a retried request with the same update ID attach to the running workflow.
                .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                // ALLOW_DUPLICATE avoids failing the operation after the workflow completes,
                // which ALLOW_DUPLICATE_FAILED_ONLY would do.
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build();
    }
}
