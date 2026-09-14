package com.acme.enablements.payments.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.CaptureChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.PaymentChargeState;
import com.acme.proto.acme.enablements.domain.enablements.v1.VoidChargeRequest;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Holds one charge's authorize/capture/void lifecycle. Workflow ID is the
 * raw charge ID, matching this repo's unprefixed entity-ID convention.
 */
@WorkflowInterface
public interface PaymentCharge {

    @WorkflowMethod
    void execute(CreateChargeRequest request);

    @UpdateMethod
    void capture(CaptureChargeRequest request);

    @UpdateMethod
    void voidCharge(VoidChargeRequest request);

    @QueryMethod
    PaymentChargeState getState();
}
