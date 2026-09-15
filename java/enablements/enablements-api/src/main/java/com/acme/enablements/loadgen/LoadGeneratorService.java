package com.acme.enablements.loadgen;

import com.acme.enablements.activities.OrderActivities;
import com.acme.proto.acme.enablements.v1.LoadGenerationState;
import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import io.temporal.api.enums.v1.ActivityExecutionStatus;
import io.temporal.api.enums.v1.ActivityIdConflictPolicy;
import io.temporal.api.enums.v1.ActivityIdReusePolicy;
import io.temporal.client.ActivityClient;
import io.temporal.client.ActivityClientOptions;
import io.temporal.client.ActivityExecutionDescription;
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
 * Starts, observes, and cancels the {@code OrderActivities.runSubmissionLoop}
 * load-generation job as a Standalone Activity: no owning workflow, so the
 * caller (this service) controls the execution directly by Activity ID.
 */
@Service
public class LoadGeneratorService {

    private static final Logger logger = LoggerFactory.getLogger(LoadGeneratorService.class);
    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);
    private static final String TASK_QUEUE = "enablements";

    private final ActivityClient activityClient;

    public LoadGeneratorService(WorkflowClient workflowClient) {
        this.activityClient = ActivityClient.newInstance(
                workflowClient.getWorkflowServiceStubs(),
                ActivityClientOptions.newBuilder()
                        .setNamespace(workflowClient.getOptions().getNamespace())
                        .build());
    }

    public LoadGenerationState start(StartWorkerVersionEnablementRequest request) {
        String enablementId = request.getEnablementId().isBlank()
                ? "load-" + ID_TIMESTAMP.format(Instant.now())
                : request.getEnablementId();
        StartWorkerVersionEnablementRequest requestWithId = request.toBuilder()
                .setEnablementId(enablementId)
                .build();

        logger.info("Starting load generator {}", enablementId);

        // Fail a duplicate start against an enablementId that's still running (mirrors this
        // path's previous default WorkflowIdReusePolicy-driven behavior via WorkflowClient);
        // allow reusing the id once a prior run under it has closed.
        StartActivityOptions options = StartActivityOptions.newBuilder()
                .setId(enablementId)
                .setTaskQueue(TASK_QUEUE)
                .setHeartbeatTimeout(Duration.ofSeconds(30))
                .setScheduleToCloseTimeout(Duration.ofHours(24))
                .setIdConflictPolicy(ActivityIdConflictPolicy.ACTIVITY_ID_CONFLICT_POLICY_FAIL)
                .setIdReusePolicy(ActivityIdReusePolicy.ACTIVITY_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                .build();

        activityClient.start(OrderActivities.class, OrderActivities::runSubmissionLoop, options, requestWithId);
        return getState(enablementId);
    }

    public void stop(String enablementId, String reason) {
        activityClient.getHandle(enablementId, null, Integer.class).cancel(reason);
    }

    public LoadGenerationState getState(String enablementId) {
        ActivityExecutionDescription description =
                activityClient.getHandle(enablementId, null, Integer.class).describe();
        int submitted = description.getHeartbeatDetails(Integer.class).orElse(0);
        return LoadGenerationState.newBuilder()
                .setEnablementId(enablementId)
                .setStatus(toExecutionStatus(description.getStatus()))
                .setOrdersSubmittedCount(submitted)
                .build();
    }

    private static LoadGenerationState.ExecutionStatus toExecutionStatus(ActivityExecutionStatus status) {
        return switch (status) {
            case ACTIVITY_EXECUTION_STATUS_RUNNING, ACTIVITY_EXECUTION_STATUS_PAUSED ->
                    LoadGenerationState.ExecutionStatus.RUNNING;
            case ACTIVITY_EXECUTION_STATUS_COMPLETED ->
                    LoadGenerationState.ExecutionStatus.COMPLETED;
            case ACTIVITY_EXECUTION_STATUS_CANCELED, ACTIVITY_EXECUTION_STATUS_TERMINATED ->
                    LoadGenerationState.ExecutionStatus.CANCELED;
            case ACTIVITY_EXECUTION_STATUS_FAILED, ACTIVITY_EXECUTION_STATUS_TIMED_OUT ->
                    LoadGenerationState.ExecutionStatus.FAILED;
            default -> LoadGenerationState.ExecutionStatus.EXECUTION_STATUS_UNSPECIFIED;
        };
    }
}
