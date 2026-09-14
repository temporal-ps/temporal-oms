package com.acme.enablements.activities;

import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceInventoryState;
import com.acme.proto.acme.enablements.domain.enablements.v1.HoldInventoryRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * WorkflowClient-backed caller for the CommerceInventory singleton, used by
 * CommerceOrder since a workflow cannot call another workflow's
 * update/query directly in-process.
 */
@ActivityInterface
public interface CommerceInventoryActivities {

    @ActivityMethod
    void hold(HoldInventoryRequest request);

    @ActivityMethod
    void release(HoldInventoryRequest request);

    @ActivityMethod
    void deduct(HoldInventoryRequest request);

    @ActivityMethod
    CommerceInventoryState getState();
}
