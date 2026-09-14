package com.acme.enablements.activities;

import com.acme.enablements.webhooks.WebhookSubscribersProperties;
import com.acme.enablements.webhooks.workflows.WebhookEvent;
import com.acme.enablements.webhooks.workflows.WebhookEventLog;
import io.temporal.activity.Activity;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WithStartWorkflowOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component("webhook-publisher-activities")
public class WebhookPublisherImpl implements WebhookPublisher {

    private static final Logger logger = LoggerFactory.getLogger(WebhookPublisherImpl.class);

    private final WorkflowClient workflowClient;
    private final WebhookSubscribersProperties subscribers;
    private final RestClient restClient;

    public WebhookPublisherImpl(WorkflowClient workflowClient, WebhookSubscribersProperties subscribers, RestClient.Builder restClientBuilder) {
        this.workflowClient = workflowClient;
        this.subscribers = subscribers;
        this.restClient = restClientBuilder.build();
    }

    @Override
    public void publish(String eventType, String orderId, String payloadJson) {
        List<WebhookSubscribersProperties.Subscriber> subscriberConfigs =
                subscribers.getSubscribers().getOrDefault(eventType, List.of());
        List<String> deliveredTo = new ArrayList<>();
        for (var subscriber : subscriberConfigs) {
            String url = subscriber.getUrl().replace("{orderId}", orderId);
            restClient.method(HttpMethod.valueOf(subscriber.getMethod().toUpperCase()))
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payloadJson)
                    .retrieve()
                    .toBodilessEntity();
            deliveredTo.add(url);
        }
        logger.info("Published {} to {} subscriber(s)", eventType, deliveredTo.size());

        int attempt = Activity.getExecutionContext().getInfo().getAttempt();
        recordEvent(new WebhookEvent(eventType, payloadJson, Instant.now(), attempt, deliveredTo));
    }

    private void recordEvent(WebhookEvent event) {
        var stub = workflowClient.newWorkflowStub(
                WebhookEventLog.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(WebhookEventLog.WORKFLOW_ID)
                        .setTaskQueue("commerce")
                        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                        .build());

        WorkflowClient.executeUpdateWithStart(
                stub::recordEvent,
                event,
                UpdateOptions.<Void>newBuilder().setWaitForStage(io.temporal.client.WorkflowUpdateStage.COMPLETED).build(),
                new WithStartWorkflowOperation<>(stub::execute, List.of()));
    }
}
