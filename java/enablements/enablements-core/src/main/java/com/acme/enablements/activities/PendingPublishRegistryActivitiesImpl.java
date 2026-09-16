package com.acme.enablements.activities;

import com.acme.enablements.commerce.workflows.PendingPublishEntry;
import com.acme.enablements.commerce.workflows.PendingPublishRegistry;
import com.acme.enablements.commerce.workflows.PublishSide;
import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateStage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("pending-publish-registry-activities")
public class PendingPublishRegistryActivitiesImpl implements PendingPublishRegistryActivities {

    private final WorkflowClient workflowClient;

    public PendingPublishRegistryActivitiesImpl(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public void registerCommerceOrder(String orderId, DemoScenario scenario, String commercePayloadJson) {
        var stub = newStub();
        WorkflowClient.executeUpdateWithStart(
                stub::registerCommerceOrder,
                orderId, scenario, commercePayloadJson,
                UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                new WithStartWorkflowOperation<>(stub::execute, List.of()));
    }

    @Override
    public void registerPaymentAuthorization(String orderId, String authorizedPayloadJson) {
        var stub = newStub();
        WorkflowClient.executeUpdateWithStart(
                stub::registerPaymentAuthorization,
                orderId, authorizedPayloadJson,
                UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                new WithStartWorkflowOperation<>(stub::execute, List.of()));
    }

    @Override
    public void registerPaymentCapture(String orderId, String paymentPayloadJson) {
        var stub = newStub();
        WorkflowClient.executeUpdateWithStart(
                stub::registerPaymentCapture,
                orderId, paymentPayloadJson,
                UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                new WithStartWorkflowOperation<>(stub::execute, List.of()));
    }

    @Override
    public void markDelivered(String orderId, PublishSide side) {
        var stub = newStub();
        WorkflowClient.executeUpdateWithStart(
                stub::markDelivered,
                orderId, side,
                UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                new WithStartWorkflowOperation<>(stub::execute, List.of()));
    }

    @Override
    public List<PendingPublishEntry> getPendingEntries() {
        try {
            return workflowClient.newWorkflowStub(PendingPublishRegistry.class, PendingPublishRegistry.WORKFLOW_ID)
                    .getPendingEntries();
        } catch (WorkflowNotFoundException e) {
            return List.of();
        }
    }

    private PendingPublishRegistry newStub() {
        return workflowClient.newWorkflowStub(
                PendingPublishRegistry.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(PendingPublishRegistry.WORKFLOW_ID)
                        .setTaskQueue("commerce")
                        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                        .build());
    }
}
