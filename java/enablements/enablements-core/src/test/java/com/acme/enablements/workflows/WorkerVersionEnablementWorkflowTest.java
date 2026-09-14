package com.acme.enablements.workflows;

import com.acme.enablements.activities.DeploymentActivities;
import com.acme.enablements.activities.OrderActivities;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionResponse;
import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import com.acme.proto.acme.enablements.v1.SubmitOneOrderRequest;
import com.acme.proto.acme.enablements.v1.SubmitOneOrderResponse;
import com.acme.proto.acme.enablements.v1.WorkerVersionEnablementState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerVersionEnablementWorkflowTest {

    private static final String TASK_QUEUE = "enablements";

    /** See PaymentChargeWorkflowTest for why this is a hand-written fake, not a Mockito mock. */
    private static class RecordingOrderActivities implements OrderActivities {
        final AtomicInteger callCount = new AtomicInteger();

        @Override
        public SubmitOneOrderResponse submitOneOrder(SubmitOneOrderRequest cmd) {
            callCount.incrementAndGet();
            return SubmitOneOrderResponse.newBuilder().setOrderId("order-x").setChargeId("charge-x").build();
        }
    }

    private static class RecordingDeploymentActivities implements DeploymentActivities {
        final List<DeployWorkerVersionRequest> deployed = new ArrayList<>();

        @Override
        public DeployWorkerVersionResponse deployWorkerVersion(DeployWorkerVersionRequest cmd) {
            deployed.add(cmd);
            return DeployWorkerVersionResponse.getDefaultInstance();
        }

        @Override
        public void registerCompatibility() {
        }
    }

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;
    private RecordingOrderActivities orderActivities;
    private RecordingDeploymentActivities deploymentActivities;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(WorkerVersionEnablementImpl.class);
        orderActivities = new RecordingOrderActivities();
        deploymentActivities = new RecordingDeploymentActivities();
        worker.registerActivitiesImplementations(orderActivities, deploymentActivities);
        testEnv.start();
        client = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private WorkerVersionEnablement newStub(String workflowId) {
        return client.newWorkflowStub(
                WorkerVersionEnablement.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(TASK_QUEUE).build());
    }

    @Test
    void submitsExactlyOrderCountAndCompletes() throws Exception {
        var stub = newStub("enablement-complete");
        var request = StartWorkerVersionEnablementRequest.newBuilder()
                .setEnablementId("demo-1").setOrderCount(3).setSubmitRatePerMin(600)
                .build();

        WorkflowClient.execute(stub::execute, request).get(30, TimeUnit.SECONDS);

        assertThat(stub.getState().getOrdersSubmittedCount()).isEqualTo(3);
        assertThat(stub.getState().getCurrentPhase()).isEqualTo(WorkerVersionEnablementState.DemoPhase.COMPLETE);
        assertThat(orderActivities.callCount.get()).isEqualTo(3);
    }

    @Test
    void deployWorkerVersionSignalNoLongerThrowsAndUsesSignalFields() throws Exception {
        var stub = newStub("enablement-deploy");
        var request = StartWorkerVersionEnablementRequest.newBuilder()
                .setEnablementId("demo-2").setOrderCount(2).setSubmitRatePerMin(600)
                .build();

        WorkflowStub.fromTyped(stub).start(request);
        stub.deployWorkerVersion(DeployWorkerVersionRequest.newBuilder()
                .setBuildId("processing-worker:v7").setVersion("v7").setReplicaCount(3).build());
        WorkflowStub.fromTyped(stub).getResult(30, TimeUnit.SECONDS, Void.class);

        var state = stub.getState();
        assertThat(state.getActiveVersionsList()).contains("processing-worker:v7");
        assertThat(deploymentActivities.deployed).hasSize(1);
        assertThat(deploymentActivities.deployed.getFirst().getBuildId()).isEqualTo("processing-worker:v7");
        assertThat(deploymentActivities.deployed.getFirst().getReplicaCount()).isEqualTo(3);
    }

    @Test
    void pauseGatesSubmissionUntilResumed() throws Exception {
        var stub = newStub("enablement-pause");
        var request = StartWorkerVersionEnablementRequest.newBuilder()
                .setEnablementId("demo-3").setOrderCount(5).setSubmitRatePerMin(60)
                .build();

        WorkflowStub.fromTyped(stub).start(request);
        stub.pause();
        testEnv.sleep(Duration.ofSeconds(10));
        assertThat(stub.getState().getOrdersSubmittedCount()).isZero();

        stub.resume();
        WorkflowStub.fromTyped(stub).getResult(30, TimeUnit.SECONDS, Void.class);
        assertThat(stub.getState().getOrdersSubmittedCount()).isEqualTo(5);
    }
}
