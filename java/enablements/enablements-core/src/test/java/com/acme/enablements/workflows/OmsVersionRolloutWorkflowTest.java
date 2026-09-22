package com.acme.enablements.workflows;

import com.acme.enablements.activities.DeploymentActivities;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionResponse;
import com.acme.proto.acme.enablements.v1.OmsVersionRolloutState;
import com.acme.proto.acme.enablements.v1.OmsVersionRolloutStep;
import com.acme.proto.acme.enablements.v1.RolloutStepStatus;
import com.acme.proto.acme.enablements.v1.StartOmsVersionRolloutRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class OmsVersionRolloutWorkflowTest {

    private static final String TASK_QUEUE = "enablements";

    /**
     * See PaymentChargeWorkflowTest for why this is a hand-written fake, not a
     * Mockito mock. Records call order and can be told to fail a specific bounded
     * context once, matching production's real idempotent-retry shape.
     */
    private static class RecordingDeploymentActivities implements DeploymentActivities {
        final List<String> deployedContextsInOrder = new ArrayList<>();
        final Map<String, String> currentBuildIds = new java.util.HashMap<>();
        String failContext;

        @Override
        public DeployWorkerVersionResponse deployWorkerVersion(DeployWorkerVersionRequest cmd) {
            if (cmd.getDeploymentName().equals(failContext)) {
                throw new RuntimeException("simulated failure for " + failContext);
            }
            deployedContextsInOrder.add(cmd.getDeploymentName());
            return DeployWorkerVersionResponse.newBuilder().setCurrentVersionSet(true).build();
        }

        @Override
        public String currentBuildId(String deploymentName) {
            return currentBuildIds.getOrDefault(deploymentName, "");
        }
    }

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;
    private RecordingDeploymentActivities deploymentActivities;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OmsVersionRolloutImpl.class);
        deploymentActivities = new RecordingDeploymentActivities();
        worker.registerActivitiesImplementations(deploymentActivities);
        testEnv.start();
        client = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private OmsVersionRollout newStub(String workflowId) {
        return client.newWorkflowStub(
                OmsVersionRollout.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(TASK_QUEUE).build());
    }

    @Test
    void forwardRolloutPromotesProcessingAndFulfillmentBeforeApps() throws Exception {
        deploymentActivities.currentBuildIds.put("apps", "v1");
        var stub = newStub("rollout-forward");
        var request = StartOmsVersionRolloutRequest.newBuilder()
                .setRolloutId("rollout-forward").setOmsVersion("v4").build();

        WorkflowClient.execute(stub::execute, request).get(30, TimeUnit.SECONDS);

        assertThat(deploymentActivities.deployedContextsInOrder)
                .containsExactly("processing", "fulfillment", "apps");
        assertThat(stub.getState().getOverallStatus()).isEqualTo(RolloutStepStatus.SUCCEEDED);
        assertThat(stub.getState().getStepsList())
                .extracting(OmsVersionRolloutStep::getStatus)
                .containsOnly(RolloutStepStatus.SUCCEEDED);
    }

    @Test
    void backwardRolloutPromotesAppsFirst() throws Exception {
        deploymentActivities.currentBuildIds.put("apps", "v3");
        var stub = newStub("rollout-backward");
        var request = StartOmsVersionRolloutRequest.newBuilder()
                .setRolloutId("rollout-backward").setOmsVersion("v1").build();

        WorkflowClient.execute(stub::execute, request).get(30, TimeUnit.SECONDS);

        // v1's fulfillment target is "embedded" (not deployable yet), so it's skipped, not attempted.
        assertThat(deploymentActivities.deployedContextsInOrder)
                .containsExactly("apps", "processing");
    }

    @Test
    void embeddedFulfillmentTargetIsSkippedNotAttempted() throws Exception {
        deploymentActivities.currentBuildIds.put("apps", "v1");
        var stub = newStub("rollout-embedded");
        var request = StartOmsVersionRolloutRequest.newBuilder()
                .setRolloutId("rollout-embedded").setOmsVersion("v2").build();

        WorkflowClient.execute(stub::execute, request).get(30, TimeUnit.SECONDS);

        assertThat(deploymentActivities.deployedContextsInOrder).containsExactly("processing", "apps");
        var fulfillmentStep = stub.getState().getStepsList().stream()
                .filter(s -> s.getBoundedContext().equals("fulfillment")).findFirst().orElseThrow();
        assertThat(fulfillmentStep.getStatus()).isEqualTo(RolloutStepStatus.SKIPPED);
    }

    @Test
    void failedStepStopsTheRolloutWithoutAttemptingLaterSteps() throws Exception {
        deploymentActivities.currentBuildIds.put("apps", "v1");
        deploymentActivities.failContext = "fulfillment";
        var stub = newStub("rollout-failure");
        var request = StartOmsVersionRolloutRequest.newBuilder()
                .setRolloutId("rollout-failure").setOmsVersion("v4").build();

        WorkflowClient.execute(stub::execute, request).get(30, TimeUnit.SECONDS);

        // processing (before the failing fulfillment step) ran; apps (after it) never did.
        assertThat(deploymentActivities.deployedContextsInOrder).containsExactly("processing");
        assertThat(stub.getState().getOverallStatus()).isEqualTo(RolloutStepStatus.FAILED);
        var steps = stub.getState().getStepsList();
        assertThat(steps.get(0).getStatus()).isEqualTo(RolloutStepStatus.SUCCEEDED); // processing
        assertThat(steps.get(1).getStatus()).isEqualTo(RolloutStepStatus.FAILED); // fulfillment
        assertThat(steps.get(2).getStatus()).isEqualTo(RolloutStepStatus.PENDING); // apps, never attempted
    }
}
