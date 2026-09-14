package com.acme.enablements.activities;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Shared event-delivery mechanism for the Commerce App and Payments
 * Processor backends. Called only by PublishCartOrders, never directly by
 * CommerceOrder or PaymentCharge.
 */
@ActivityInterface
public interface WebhookPublisher {

    @ActivityMethod
    void publish(String eventType, String payloadJson);
}
