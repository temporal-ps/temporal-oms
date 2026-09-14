package com.acme.enablements.commerce.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Durable ledger of placed orders awaiting webhook delivery: one entry per
 * order ID, holding each side's payload and delivered flag. Fixed workflow
 * ID (singleton), continueAsNew-bounded. Called only via activities, never
 * queried/updated directly in-process by another workflow.
 */
@WorkflowInterface
public interface PendingPublishRegistry {

    String WORKFLOW_ID = "pending-publish-registry";

    @WorkflowMethod
    void execute(List<PendingPublishEntry> carriedForwardEntries);

    @UpdateMethod
    void registerCommerceOrder(String orderId, DemoScenario scenario, String commercePayloadJson);

    @UpdateMethod
    void registerPaymentAuthorization(String orderId, String authorizedPayloadJson);

    @UpdateMethod
    void registerPaymentCapture(String orderId, String paymentPayloadJson);

    @UpdateMethod
    void markDelivered(String orderId, PublishSide side);

    @QueryMethod
    List<PendingPublishEntry> getPendingEntries();
}
