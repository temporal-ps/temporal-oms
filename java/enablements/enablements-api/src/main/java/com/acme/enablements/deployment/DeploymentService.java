package com.acme.enablements.deployment;

import com.acme.enablements.activities.DeploymentActivities;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionResponse;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.WorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Promotes a bounded context (apps, processing, fulfillment) to a new component
 * version as a Standalone Activity: no owning workflow, independent of load
 * generation (hosting.md Mode B). Mirrors {@code LoadGeneratorService}'s pattern of
 * calling an existing activity directly via {@link ActivityClient} rather than
 * through {@code WorkerVersionEnablementImpl}, which couples load and deploy.
 */
@Service
public class DeploymentService {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentService.class);
    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);
    private static final String TASK_QUEUE = "enablements";

    private final ActivityClient activityClient;

    public DeploymentService(WorkflowClient workflowClient) {
        this.activityClient = ActivityClient.newInstance(
                workflowClient.getWorkflowServiceStubs(),
                ActivityClientOptions.newBuilder()
                        .setNamespace(workflowClient.getOptions().getNamespace())
                        .build());
    }

    public DeployWorkerVersionResponse deploy(DeployWorkerVersionRequest request) {
        String activityId = "deploy-" + request.getDeploymentName() + "-" + ID_TIMESTAMP.format(Instant.now());
        logger.info("Promoting {} to buildId={}", request.getDeploymentName(), request.getBuildId());

        StartActivityOptions options = StartActivityOptions.newBuilder()
                .setId(activityId)
                .setTaskQueue(TASK_QUEUE)
                .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                .build();

        return activityClient.execute(DeploymentActivities.class, DeploymentActivities::deployWorkerVersion, options, request);
    }
}
