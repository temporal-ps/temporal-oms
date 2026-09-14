package com.acme.enablements.commerce.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceOrderState;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateCommerceOrderRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Holds one placed order's state, including its selected demo scenario.
 * Workflow ID is the order ID, unprefixed, matching this repo's existing
 * entity-ID convention.
 */
@WorkflowInterface
public interface CommerceOrder {

    @WorkflowMethod
    void execute(CreateCommerceOrderRequest request);

    @QueryMethod
    CommerceOrderState getState();
}
