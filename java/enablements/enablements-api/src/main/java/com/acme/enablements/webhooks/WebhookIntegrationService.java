package com.acme.enablements.webhooks;

import com.acme.enablements.webhooks.workflows.WebhookEvent;
import com.acme.enablements.webhooks.workflows.WebhookEventLog;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebhookIntegrationService {

    private final WorkflowClient workflowClient;

    public WebhookIntegrationService(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public List<WebhookEvent> getRecentEvents() {
        try {
            return workflowClient.newWorkflowStub(WebhookEventLog.class, WebhookEventLog.WORKFLOW_ID).getRecentEvents();
        } catch (WorkflowNotFoundException e) {
            return List.of();
        }
    }
}
