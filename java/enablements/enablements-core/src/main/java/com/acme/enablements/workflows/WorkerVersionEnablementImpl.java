package com.acme.enablements.workflows;

import com.acme.enablements.activities.DeploymentActivities;
import com.acme.enablements.activities.OrderActivities;
import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.ScenarioWeight;
import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import com.acme.proto.acme.enablements.v1.SubmitOneOrderRequest;
import com.acme.proto.acme.enablements.v1.WorkerVersionEnablementState;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;

/**
 * Drives order generation at a configured rate and DemoScenario mix while
 * the OMS handles all real processing; tracks only this workflow's own
 * execution state (phase, submission count, active versions). The
 * submission loop runs in the workflow itself, paced with Workflow.sleep
 * and continueAsNew-bounded, rather than inside a single long-lived
 * activity.
 */
public class WorkerVersionEnablementImpl implements WorkerVersionEnablement {

    private static final Logger logger = Workflow.getLogger(WorkerVersionEnablementImpl.class);
    private static final int CONTINUE_AS_NEW_EVERY_N_ORDERS = 100;

    private static final List<ScenarioWeight> DEFAULT_SCENARIO_WEIGHTS = List.of(
            ScenarioWeight.newBuilder().setScenario(DemoScenario.NORMAL).setWeight(85).build(),
            ScenarioWeight.newBuilder().setScenario(DemoScenario.PAYMENT_BEFORE_COMMERCE).setWeight(5).build(),
            ScenarioWeight.newBuilder().setScenario(DemoScenario.MISSING_COMMERCE_EVENT).setWeight(5).build(),
            ScenarioWeight.newBuilder().setScenario(DemoScenario.MISSING_PAYMENT_EVENT).setWeight(5).build());

    private final OrderActivities orderActivities = Workflow.newActivityStub(
            OrderActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    private final DeploymentActivities deploymentActivities = Workflow.newActivityStub(
            DeploymentActivities.class,
            ActivityOptions.newBuilder()
                    .setHeartbeatTimeout(Duration.ofSeconds(30))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                    .build());

    private WorkerVersionEnablementState state;
    private boolean paused = false;
    private int ordersSubmittedThisExecution = 0;

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
        long sleepIntervalMs = req.getSubmitRatePerMin() > 0 ? (60_000L / req.getSubmitRatePerMin()) : 5_000L;

        while (state.getOrdersSubmittedCount() < req.getOrderCount() && Workflow.currentTimeMillis() < deadline) {
            processQueuedDeployRequests();

            Workflow.await(() -> !paused);
            Workflow.sleep(Duration.ofMillis(sleepIntervalMs));
            if (paused) {
                continue;
            }

            var scenario = pickWeightedScenario(req.getScenarioWeightsList());
            var response = orderActivities.submitOneOrder(SubmitOneOrderRequest.newBuilder()
                    .setEnablementId(req.getEnablementId())
                    .setOrderIdPrefix(req.hasOrderIdSeed() ? req.getOrderIdSeed() : req.getEnablementId())
                    .setScenario(scenario)
                    .build());
            logger.debug("Submitted order {} / charge {} (scenario={})", response.getOrderId(), response.getChargeId(), scenario);

            state = state.toBuilder().setOrdersSubmittedCount(state.getOrdersSubmittedCount() + 1).build();
            ordersSubmittedThisExecution++;

            if (ordersSubmittedThisExecution >= CONTINUE_AS_NEW_EVERY_N_ORDERS) {
                Workflow.await(Workflow::isEveryHandlerFinished);
                var remaining = req.toBuilder()
                        .setOrderCount(req.getOrderCount() - state.getOrdersSubmittedCount())
                        .build();
                Workflow.newContinueAsNewStub(WorkerVersionEnablement.class).execute(remaining);
                return;
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

    private DemoScenario pickWeightedScenario(List<ScenarioWeight> configured) {
        List<ScenarioWeight> weights = configured.isEmpty() ? DEFAULT_SCENARIO_WEIGHTS : configured;
        int total = weights.stream().mapToInt(ScenarioWeight::getWeight).sum();
        if (total <= 0) {
            return DemoScenario.NORMAL;
        }
        int pick = Workflow.newRandom().nextInt(total);
        int cumulative = 0;
        for (var weight : weights) {
            cumulative += weight.getWeight();
            if (pick < cumulative) {
                return weight.getScenario();
            }
        }
        return DemoScenario.NORMAL;
    }

    private static Timestamp nowTimestamp() {
        long millis = Workflow.currentTimeMillis();
        return Timestamp.newBuilder()
                .setSeconds(millis / 1000)
                .setNanos((int) ((millis % 1000) * 1_000_000))
                .build();
    }
}
