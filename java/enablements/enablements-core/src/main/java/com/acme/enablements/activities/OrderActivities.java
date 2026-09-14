package com.acme.enablements.activities;

import com.acme.proto.acme.enablements.v1.SubmitOneOrderRequest;
import com.acme.proto.acme.enablements.v1.SubmitOneOrderResponse;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Submits one order to the Commerce App / Payments Processor backends
 * (SPECS/commerce-payments-apps/spec.md) during enablement demonstrations.
 */
@ActivityInterface
public interface OrderActivities {

    /**
     * Submit one order and its charge, carrying the workflow-chosen
     * DemoScenario. Real delivery into apps-api's webhooks happens later,
     * asynchronously, via PublishCartOrders' scheduled tick.
     */
    @ActivityMethod
    SubmitOneOrderResponse submitOneOrder(SubmitOneOrderRequest cmd);
}
