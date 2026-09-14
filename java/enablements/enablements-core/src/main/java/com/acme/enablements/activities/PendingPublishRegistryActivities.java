package com.acme.enablements.activities;

import com.acme.enablements.commerce.workflows.PendingPublishEntry;
import com.acme.enablements.commerce.workflows.PublishSide;
import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/**
 * WorkflowClient-backed query/update callers for PendingPublishRegistry,
 * used by CommerceOrder, PaymentCharge, and PublishCartOrders since a
 * workflow cannot call another workflow's update/query directly in-process.
 */
@ActivityInterface
public interface PendingPublishRegistryActivities {

    @ActivityMethod
    void registerCommerceOrder(String orderId, DemoScenario scenario, String commercePayloadJson);

    @ActivityMethod
    void registerPaymentAuthorization(String orderId, String authorizedPayloadJson);

    @ActivityMethod
    void registerPaymentCapture(String orderId, String paymentPayloadJson);

    @ActivityMethod
    void markDelivered(String orderId, PublishSide side);

    @ActivityMethod
    List<PendingPublishEntry> getPendingEntries();
}
