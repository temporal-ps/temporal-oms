package com.acme.enablements.workflows;

import com.acme.enablements.activities.DeploymentActivities;
import com.acme.enablements.activities.OrderActivities;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import com.acme.proto.acme.enablements.v1.WorkerVersionEnablementState;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * Drives order generation at a configured rate and DemoScenario mix while
 * the OMS handles all real processing; tracks only this workflow's own
 * execution state (phase, submission count, active versions). The
 * submission loop itself runs inside a single long-lived, heartbeating
 * activity (see OrderActivities.runSubmissionLoop) rather than in the
 * workflow: pausing cancels the cancellation scope wrapping that activity
 * call, and resuming re-invokes it, so there's no per-order workflow
 * history growth and no need for continueAsNew.
 */
public class WorkerVersionEnablementImpl implements WorkerVersionEnablement {

    private static final Logger logger = Workflow.getLogger(WorkerVersionEnablementImpl.class);

    // Cancelling the scope wrapping this activity always fails that call with a
    // CanceledFailure (true for every ActivityCancellationType) -- WAIT_CANCELLATION_COMPLETED
    // only controls when: it waits for the activity to actually stop (observe cancellation via
    // heartbeat) so the CanceledFailure's heartbeat details reflect confirmed progress, rather
    // than TRY_CANCEL's immediately-stale count. Progress is read back from those heartbeat
    // details in execute() below, not from a return value.
    private final OrderActivities orderActivities = Workflow.newActivityStub(
            OrderActivities.class,
            ActivityOptions.newBuilder()
                    .setHeartbeatTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofHours(24))
                    .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                    .build());

    private final DeploymentActivities deploymentActivities = Workflow.newActivityStub(
            DeploymentActivities.class,
            ActivityOptions.newBuilder()
                    .setHeartbeatTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                    .build());

    private WorkerVersionEnablementState state;
    private boolean paused = false;
    private CancellationScope activeSubmission;

    @WorkflowInit
    public WorkerVersionEnablementImpl(StartWorkerVersionEnablementRequest request) {
        this.state = WorkerVersionEnablementState.newBuilder()
                .setEnablementId(request.getEnablementId())
                .setArgs(request)
                .setCurrentPhase(WorkerVersionEnablementState.DemoPhase.RUNNING_V1_ONLY)
                .addActiveVersions("v1")
                .setOrdersPerMinute(request.getSubmitRatePerMin())
                .build();
    }

    @Override
    public void execute(StartWorkerVersionEnablementRequest req) {
        logger.info(
                "Starting enablement demonstration: {} with {} orders at {}/min",
                req.getEnablementId(), req.getOrderCount(), req.getSubmitRatePerMin());

        long deadline = req.hasTimeout()
                ? Workflow.currentTimeMillis() + Durations.toMillis(req.getTimeout())
                : Long.MAX_VALUE;

        while (state.getOrdersSubmittedCount() < req.getOrderCount() && Workflow.currentTimeMillis() < deadline) {
            processQueuedDeployRequests();

            var remaining = req.toBuilder()
                    .setOrderCount(req.getOrderCount() - state.getOrdersSubmittedCount())
                    .build();
            int submittedThisRun;
            try {
                int[] result = new int[1];
                activeSubmission = Workflow.newCancellationScope(() ->
                        result[0] = orderActivities.runSubmissionLoop(remaining));
                activeSubmission.run();
                submittedThisRun = result[0];
            } catch (ActivityFailure e) {
                if (e.getCause() instanceof CanceledFailure cf) {
                    submittedThisRun = cf.getDetails().getSize() > 0 ? cf.getDetails().get(Integer.class) : 0;
                    logger.info("Submission loop cancelled; heartbeat-reported progress: {}", submittedThisRun);
                } else {
                    throw e;
                }
            } finally {
                activeSubmission = null;
            }

            state = state.toBuilder()
                    .setOrdersSubmittedCount(state.getOrdersSubmittedCount() + submittedThisRun)
                    .build();

            if (submittedThisRun < remaining.getOrderCount()) {
                // Returned early: either paused (wait here for resume) or a deploy
                // request preempted it (nothing to wait for; loop straight back
                // around to processQueuedDeployRequests()).
                Workflow.await(() -> !paused);
            }
        }

        processQueuedDeployRequests();
        state = state.toBuilder().setCurrentPhase(WorkerVersionEnablementState.DemoPhase.COMPLETE).build();
        Workflow.await(Workflow::isEveryHandlerFinished);
    }

    @Override
    public WorkerVersionEnablementState getState() {
        return state;
    }

    @Override
    public void pause() {
        paused = true;
        if (activeSubmission != null) {
            activeSubmission.cancel();
        }
        logger.info("Enablement workflow paused");
    }

    @Override
    public void resume() {
        paused = false;
        logger.info("Enablement workflow resumed");
    }

    @Override
    public void deployWorkerVersion(DeployWorkerVersionRequest cmd) {
        state = state.toBuilder().addDeployRequests(cmd).build();
        if (state.getCurrentPhase() == WorkerVersionEnablementState.DemoPhase.RUNNING_V1_ONLY) {
            state = state.toBuilder().setCurrentPhase(WorkerVersionEnablementState.DemoPhase.TRANSITIONING_TO_V2).build();
        }
        // Preempt the in-flight submission loop so the deploy is processed promptly
        // instead of waiting for the loop to pause/exhaust order_count on its own.
        if (activeSubmission != null) {
            activeSubmission.cancel();
        }
    }

    private void processQueuedDeployRequests() {
        while (state.getDeployRequestsCount() > 0) {
            var cmd = state.getDeployRequests(0);
            var result = deploymentActivities.deployWorkerVersion(cmd);
            deploymentActivities.registerCompatibility();
            state = state.toBuilder()
                    .removeDeployRequests(0)
                    .addDeployments(result)
                    .addActiveVersions(cmd.getBuildId())
                    .setCurrentPhase(WorkerVersionEnablementState.DemoPhase.RUNNING_BOTH)
                    .setLastTransitionAt(nowTimestamp())
                    .build();
        }
    }

    private static Timestamp nowTimestamp() {
        long millis = Workflow.currentTimeMillis();
        return Timestamp.newBuilder()
                .setSeconds(millis / 1000)
                .setNanos((int) ((millis % 1000) * 1_000_000))
                .build();
    }
}
