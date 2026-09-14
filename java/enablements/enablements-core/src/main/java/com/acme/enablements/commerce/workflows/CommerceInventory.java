package com.acme.enablements.commerce.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceInventoryState;
import com.acme.proto.acme.enablements.domain.enablements.v1.HoldInventoryRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Catalog-wide stock ledger, distinct from and unrelated to the OMS's own
 * fulfillment-side InventoryIntegrationService. Fixed workflow ID
 * (singleton), matching SupportTeam's convention.
 */
@WorkflowInterface
public interface CommerceInventory {

    String WORKFLOW_ID = "commerce-inventory";

    @WorkflowMethod
    void execute(CommerceInventoryState initialState);

    @UpdateMethod
    void hold(HoldInventoryRequest request);

    @UpdateMethod
    void release(HoldInventoryRequest request);

    @UpdateMethod
    void deduct(HoldInventoryRequest request);

    @QueryMethod
    CommerceInventoryState getState();
}
