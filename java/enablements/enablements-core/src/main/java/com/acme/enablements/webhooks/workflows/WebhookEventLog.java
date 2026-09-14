package com.acme.enablements.webhooks.workflows;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Temporal-backed recent-events log for WebhookPublisher, queryable from a
 * different process than the one running the publish activity. Fixed
 * workflow ID (singleton), continueAsNew-bounded ring buffer.
 */
@WorkflowInterface
public interface WebhookEventLog {

    String WORKFLOW_ID = "webhook-event-log";

    @WorkflowMethod
    void execute(List<WebhookEvent> carriedForwardEvents);

    @UpdateMethod
    void recordEvent(WebhookEvent event);

    @QueryMethod
    List<WebhookEvent> getRecentEvents();
}
