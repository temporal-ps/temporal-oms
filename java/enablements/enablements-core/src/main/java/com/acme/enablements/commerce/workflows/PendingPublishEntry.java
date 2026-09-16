package com.acme.enablements.commerce.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;

import java.util.Optional;

/**
 * One order's pending-publish ledger entry. Internal workflow state, not a
 * wire proto: PendingPublishRegistry is its only owner.
 */
public record PendingPublishEntry(
        String orderId,
        DemoScenario scenario,
        Optional<String> authorizedPayloadJson,
        boolean authorizedDelivered,
        Optional<String> commercePayloadJson,
        Optional<String> paymentPayloadJson,
        boolean commerceDelivered,
        boolean paymentDelivered) {

    public boolean hasUndeliveredEligibleSide() {
        return (authorizedPayloadJson.isPresent() && !authorizedDelivered)
                || (commercePayloadJson.isPresent() && !commerceDelivered)
                || (paymentPayloadJson.isPresent() && !paymentDelivered);
    }
}
