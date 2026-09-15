package com.acme.enablements.activities;

import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Submits orders to the Commerce App / Payments Processor backends
 * (SPECS/commerce-payments-apps/spec.md) during enablement demonstrations.
 */
@ActivityInterface
public interface OrderActivities {

    /**
     * Runs the order-submission loop until order_count or the request's
     * timeout is reached, heartbeating after every order so the calling
     * workflow can cancel it (pause) or preempt it (a queued deploy
     * request). Does not catch its own cancellation: the calling workflow
     * recovers progress from the resulting CanceledFailure's heartbeat
     * details, not from a return value.
     */
    @ActivityMethod
    int runSubmissionLoop(StartWorkerVersionEnablementRequest req);
}
