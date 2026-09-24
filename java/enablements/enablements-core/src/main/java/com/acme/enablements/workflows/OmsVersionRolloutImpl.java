package com.acme.enablements.workflows;

import com.acme.enablements.activities.DeploymentActivities;
import com.acme.enablements.deployment.OmsVersionCatalog;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.OmsVersionRolloutState;
import com.acme.proto.acme.enablements.v1.OmsVersionRolloutStep;
import com.acme.proto.acme.enablements.v1.OmsVersionRow;
import com.acme.proto.acme.enablements.v1.RolloutStepStatus;
import com.acme.proto.acme.enablements.v1.StartOmsVersionRolloutRequest;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;

/**
 * Calls {@code DeploymentActivities.deployWorkerVersion} once per bounded
 * context needed for the target OMS version, skipping fulfillment when its
 * target is {@code embedded} (not a deployable component yet). Two named
 * paths, chosen by comparing the target apps version to the currently
 * deployed one:
 *
 * <ul>
 *   <li>Upgrade (target apps &gt;= current): fulfillment, then processing,
 *       then apps last, since apps is the one that starts depending on the
 *       others being ready first.</li>
 *   <li>Downgrade (target apps &lt; current): apps first, then fulfillment,
 *       then processing, since the older processing/fulfillment apps is
 *       about to run alongside doesn't understand a newer apps' handoff.</li>
 * </ul>
 *
 * Stops at the first failed step; it does not attempt the remaining
 * contexts.
 */
public class OmsVersionRolloutImpl implements OmsVersionRollout {

    private static final Logger logger = Workflow.getLogger(OmsVersionRolloutImpl.class);

    private final DeploymentActivities deploymentActivities = Workflow.newActivityStub(
            DeploymentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(2))
                            .setBackoffCoefficient(2.0)
                            .setMaximumInterval(Duration.ofSeconds(30))
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    private OmsVersionRolloutState state;

    @WorkflowInit
    public OmsVersionRolloutImpl(StartOmsVersionRolloutRequest request) {
        this.state = OmsVersionRolloutState.newBuilder()
                .setRolloutId(request.getRolloutId())
                .setOmsVersion(request.getOmsVersion())
                .setOverallStatus(RolloutStepStatus.PENDING)
                .build();
    }

    @Override
    public void execute(StartOmsVersionRolloutRequest request) {
        OmsVersionRow target = OmsVersionCatalog.find(request.getOmsVersion())
                .orElseThrow(() -> new IllegalArgumentException("Unknown OMS version: " + request.getOmsVersion()));

        String currentAppsBuildId = deploymentActivities.currentBuildId("apps");
        List<OmsVersionRolloutStep> plan = planSteps(target, currentAppsBuildId);
        logger.info("Rolling out OMS {} ({} apps={} from {}): steps={}",
                target.getOmsVersion(), request.getRolloutId(), target.getAppsVersion(), currentAppsBuildId, plan);

        state = state.toBuilder()
                .setOverallStatus(RolloutStepStatus.IN_PROGRESS)
                .addAllSteps(plan)
                .build();

        for (int i = 0; i < state.getStepsCount(); i++) {
            if (state.getSteps(i).getStatus() == RolloutStepStatus.SKIPPED) {
                continue;
            }
            if (!runStep(i)) {
                return; // fail-fast: stop, don't attempt the remaining contexts
            }
        }

        state = state.toBuilder().setOverallStatus(RolloutStepStatus.SUCCEEDED).build();
    }

    @Override
    public OmsVersionRolloutState getState() {
        return state;
    }

    /** Runs one step, updating state in place. Returns false if the step failed. */
    private boolean runStep(int index) {
        OmsVersionRolloutStep step = state.getSteps(index);
        updateStep(index, step.toBuilder().setStatus(RolloutStepStatus.IN_PROGRESS).build());

        try {
            var result = deploymentActivities.deployWorkerVersion(DeployWorkerVersionRequest.newBuilder()
                    .setDeploymentName(step.getBoundedContext())
                    .setVersion(step.getTargetVersion())
                    .setBuildId(step.getTargetVersion())
                    .build());
            if (!result.getCurrentVersionSet()) {
                // deployWorkerVersion didn't throw, but set-current-version never confirmed
                // within its retry window (e.g. the new pod's pollers hadn't registered
                // yet) - Temporal is still routing new work to whatever was current
                // before. Treat that as a failed step, not a silent success.
                updateStep(index, step.toBuilder()
                        .setStatus(RolloutStepStatus.FAILED)
                        .setErrorMessage("set-current-version did not confirm within its retry window")
                        .setResult(result)
                        .build());
                state = state.toBuilder()
                        .setOverallStatus(RolloutStepStatus.FAILED)
                        .setErrorMessage(step.getBoundedContext() + " failed: set-current-version did not confirm")
                        .build();
                return false;
            }
            updateStep(index, step.toBuilder()
                    .setStatus(RolloutStepStatus.SUCCEEDED)
                    .setResult(result)
                    .build());
            return true;
        } catch (ActivityFailure e) {
            String message = e.getCause() != null && e.getCause().getMessage() != null
                    ? e.getCause().getMessage() : e.getMessage();
            updateStep(index, step.toBuilder()
                    .setStatus(RolloutStepStatus.FAILED)
                    .setErrorMessage(message == null ? e.toString() : message)
                    .build());
            state = state.toBuilder()
                    .setOverallStatus(RolloutStepStatus.FAILED)
                    .setErrorMessage(step.getBoundedContext() + " failed: " + message)
                    .build();
            return false;
        }
    }

    private void updateStep(int index, OmsVersionRolloutStep updated) {
        state = state.toBuilder().setSteps(index, updated).build();
    }

    private static List<OmsVersionRolloutStep> planSteps(OmsVersionRow target, String currentAppsBuildId) {
        OmsVersionRolloutStep appsStep = pendingStep("apps", target.getAppsVersion());
        OmsVersionRolloutStep processingStep = pendingStep("processing", target.getProcessingVersion());
        OmsVersionRolloutStep fulfillmentStep = OmsVersionCatalog.FULFILLMENT_EMBEDDED.equals(target.getFulfillmentVersion())
                ? pendingStep("fulfillment", target.getFulfillmentVersion()).toBuilder()
                        .setStatus(RolloutStepStatus.SKIPPED).build()
                : pendingStep("fulfillment", target.getFulfillmentVersion());

        int direction = Integer.compare(versionNumber(target.getAppsVersion()), versionNumber(currentAppsBuildId));
        // Downgrade (target apps < current apps): apps must land first, since the OLDER
        // processing/fulfillment it's about to run alongside doesn't understand the
        // newer apps' handoff. Upgrade or unchanged: apps goes last. Fulfillment goes
        // before processing in both paths; that ordering isn't part of the identified
        // unsafe pairing (which is specifically about apps/processing), so it's free to
        // fix at fulfillment-first for consistency.
        return direction < 0
                ? List.of(appsStep, fulfillmentStep, processingStep)
                : List.of(fulfillmentStep, processingStep, appsStep);
    }

    private static OmsVersionRolloutStep pendingStep(String boundedContext, String targetVersion) {
        return OmsVersionRolloutStep.newBuilder()
                .setBoundedContext(boundedContext)
                .setTargetVersion(targetVersion)
                .setStatus(RolloutStepStatus.PENDING)
                .build();
    }

    /** "v3" -> 3; blank/unparseable (no current version yet) -> 0. */
    private static int versionNumber(String version) {
        if (version == null || version.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(version.replaceFirst("^v", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
