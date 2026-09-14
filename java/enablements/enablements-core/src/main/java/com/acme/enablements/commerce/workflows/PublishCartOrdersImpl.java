package com.acme.enablements.commerce.workflows;

import com.acme.enablements.activities.PendingPublishRegistryActivities;
import com.acme.enablements.activities.WebhookPublisher;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class PublishCartOrdersImpl implements PublishCartOrders {

    private final PendingPublishRegistryActivities registry = Workflow.newActivityStub(
            PendingPublishRegistryActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    private final WebhookPublisher webhookPublisher = Workflow.newActivityStub(
            WebhookPublisher.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    @Override
    public void execute() {
        for (var entry : registry.getPendingEntries()) {
            deliverAuthorized(entry);
            deliverCommerceAndPayment(entry);
        }
    }

    private void deliverAuthorized(PendingPublishEntry entry) {
        if (entry.authorizedPayloadJson().isPresent() && !entry.authorizedDelivered()) {
            webhookPublisher.publish("payment.authorized", entry.orderId(), entry.authorizedPayloadJson().get());
            registry.markDelivered(entry.orderId(), PublishSide.AUTHORIZED);
        }
    }

    private void deliverCommerceAndPayment(PendingPublishEntry entry) {
        boolean commerceDue;
        boolean paymentDue;
        switch (entry.scenario()) {
            case PAYMENT_BEFORE_COMMERCE -> {
                paymentDue = isDue(entry.paymentPayloadJson(), entry.paymentDelivered());
                commerceDue = isDue(entry.commercePayloadJson(), entry.commerceDelivered()) && entry.paymentDelivered();
            }
            case MISSING_COMMERCE_EVENT -> {
                paymentDue = isDue(entry.paymentPayloadJson(), entry.paymentDelivered());
                commerceDue = false;
            }
            case MISSING_PAYMENT_EVENT -> {
                commerceDue = isDue(entry.commercePayloadJson(), entry.commerceDelivered());
                paymentDue = false;
            }
            default -> {
                commerceDue = isDue(entry.commercePayloadJson(), entry.commerceDelivered());
                paymentDue = isDue(entry.paymentPayloadJson(), entry.paymentDelivered());
            }
        }

        if (commerceDue) {
            webhookPublisher.publish("commerce.order.submitted", entry.orderId(), entry.commercePayloadJson().orElseThrow());
            registry.markDelivered(entry.orderId(), PublishSide.COMMERCE);
        }
        if (paymentDue) {
            webhookPublisher.publish("payment.captured", entry.orderId(), entry.paymentPayloadJson().orElseThrow());
            registry.markDelivered(entry.orderId(), PublishSide.PAYMENT);
        }
    }

    private static boolean isDue(java.util.Optional<String> payload, boolean delivered) {
        return payload.isPresent() && !delivered;
    }
}
