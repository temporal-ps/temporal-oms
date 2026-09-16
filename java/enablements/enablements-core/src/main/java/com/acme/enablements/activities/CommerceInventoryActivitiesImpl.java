package com.acme.enablements.activities;

import com.acme.enablements.commerce.workflows.CommerceInventory;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceInventoryState;
import com.acme.proto.acme.enablements.domain.enablements.v1.HoldInventoryRequest;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowUpdateStage;
import org.springframework.stereotype.Component;

@Component("commerce-inventory-activities")
public class CommerceInventoryActivitiesImpl implements CommerceInventoryActivities {

    private final WorkflowClient workflowClient;

    public CommerceInventoryActivitiesImpl(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @Override
    public void hold(HoldInventoryRequest request) {
        var stub = newStub();
        WorkflowClient.executeUpdateWithStart(
                stub::hold, request,
                UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                new WithStartWorkflowOperation<>(stub::execute, CommerceInventoryState.getDefaultInstance()));
    }

    @Override
    public void release(HoldInventoryRequest request) {
        var stub = newStub();
        WorkflowClient.executeUpdateWithStart(
                stub::release, request,
                UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                new WithStartWorkflowOperation<>(stub::execute, CommerceInventoryState.getDefaultInstance()));
    }

    @Override
    public void deduct(HoldInventoryRequest request) {
        var stub = newStub();
        WorkflowClient.executeUpdateWithStart(
                stub::deduct, request,
                UpdateOptions.<Void>newBuilder().setWaitForStage(WorkflowUpdateStage.COMPLETED).build(),
                new WithStartWorkflowOperation<>(stub::execute, CommerceInventoryState.getDefaultInstance()));
    }

    @Override
    public CommerceInventoryState getState() {
        return workflowClient.newWorkflowStub(CommerceInventory.class, CommerceInventory.WORKFLOW_ID).getState();
    }

    private CommerceInventory newStub() {
        return workflowClient.newWorkflowStub(
                CommerceInventory.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(CommerceInventory.WORKFLOW_ID)
                        .setTaskQueue("commerce")
                        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                        .build());
    }
}
