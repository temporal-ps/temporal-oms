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
import io.temporal.nexus.Nexus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component("apps-service")
@ServiceImpl(service = AppsService.class)
public class AppsServiceImpl {
    private Logger logger = LoggerFactory.getLogger(AppsServiceImpl.class);

    @OperationImpl
    public OperationHandler<CapturePaymentRequest, GetCompleteOrderStateResponse> capturePayment() {
        return OperationHandler.sync((ctx, details,  request) -> {

            var orderId = request.getOrderId();
            var wfOpts = WorkflowOptions.newBuilder()
                    .setWorkflowId(orderId)
                    .setTaskQueue("apps")
                    .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                    // using ALLOW_DUPLICATE so we won't fail the operation if the workflow completes
                    // as it would if we used ALLOW_DUPLICATE_FAILED_ONLY
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                    .build();
            var tcli = Nexus.getOperationContext().getWorkflowClient();
            var workflow = tcli.newWorkflowStub(Order.class, wfOpts);

            // UpdateWithStart: start the workflow if needed and wait for the update result.
            // this is safe for synchronously, quickly scheduling this command
            return WorkflowClient.executeUpdateWithStart(
                    workflow::capturePayment,
                    request,
                    UpdateOptions.<GetCompleteOrderStateResponse>newBuilder()
                            .setUpdateId(details.getRequestId())
                            .build(),
                    new WithStartWorkflowOperation<>(workflow::execute, request.getCompleteOrderRequest()));
        });
    }

    @OperationImpl
    public OperationHandler<SubmitOrderRequest, GetCompleteOrderStateResponse> submitOrder() {
        return OperationHandler.sync((ctx, details,  request) -> {
            var orderId = request.getOrderId();
            var wfOpts = WorkflowOptions.newBuilder()
                    .setWorkflowId(orderId)
                    .setTaskQueue("apps")
                    .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_FAIL)
                    // using ALLOW_DUPLICATE so we won't fail the operation if the workflow completes
                    // as it would if we used ALLOW_DUPLICATE_FAILED_ONLY
                    .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                    .build();
            var tcli = Nexus.getOperationContext().getWorkflowClient();
            var workflow = tcli.newWorkflowStub(Order.class, wfOpts);

            // UpdateWithStart: start the workflow if needed and wait for the update result.
            // this is safe for synchronously, quickly scheduling this command
            return WorkflowClient.executeUpdateWithStart(
                    workflow::submitOrder,
                    request,
                    UpdateOptions.<GetCompleteOrderStateResponse>newBuilder()
                            .setUpdateId(details.getRequestId())
                            .build(),
                    new WithStartWorkflowOperation<>(workflow::execute, request.getCompleteOrderRequest()));
        });
    }

}
