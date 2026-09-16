package com.acme.enablements.commerce.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One short-lived run per Temporal Schedule tick: loads pending entries and
 * delivers whichever sides each entry's DemoScenario says are due.
 */
@WorkflowInterface
public interface PublishCartOrders {

    @WorkflowMethod
    void execute();
}
