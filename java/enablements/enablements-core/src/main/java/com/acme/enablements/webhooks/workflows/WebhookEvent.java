package com.acme.enablements.webhooks.workflows;

import java.time.Instant;
import java.util.List;

/**
 * One publish attempt (success or exhausted failure), recorded regardless
 * of whether any subscriber URLs are configured. Internal record, not a
 * wire proto: WebhookEventLog is its only owner.
 */
public record WebhookEvent(
        String eventType,
        String payloadJson,
        Instant publishedAt,
        int deliveryAttempts,
        List<String> deliveredTo) {
}
