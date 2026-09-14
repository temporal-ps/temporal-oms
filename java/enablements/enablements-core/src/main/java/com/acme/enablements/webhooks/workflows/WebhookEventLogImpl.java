package com.acme.enablements.webhooks.workflows;

import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class WebhookEventLogImpl implements WebhookEventLog {

    private static final int MAX_RETAINED_EVENTS = 200;
    private static final int CONTINUE_AS_NEW_EVERY_N_EVENTS = 500;

    private final Deque<WebhookEvent> events;
    private int recordedSinceStart = 0;

    @WorkflowInit
    public WebhookEventLogImpl(List<WebhookEvent> carriedForwardEvents) {
        this.events = new ArrayDeque<>(carriedForwardEvents);
    }

    @Override
    public void execute(List<WebhookEvent> carriedForwardEvents) {
        Workflow.await(() -> recordedSinceStart >= CONTINUE_AS_NEW_EVERY_N_EVENTS);
        Workflow.await(Workflow::isEveryHandlerFinished);
        var next = Workflow.newContinueAsNewStub(WebhookEventLog.class);
        next.execute(new ArrayList<>(events));
    }

    @Override
    public void recordEvent(WebhookEvent event) {
        events.addFirst(event);
        while (events.size() > MAX_RETAINED_EVENTS) {
            events.removeLast();
        }
        recordedSinceStart++;
    }

    @Override
    public List<WebhookEvent> getRecentEvents() {
        return new ArrayList<>(events);
    }
}
