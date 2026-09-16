package com.acme.enablements.payments.workflows;

import com.acme.enablements.activities.PendingPublishRegistryActivities;
import com.acme.enablements.commerce.workflows.PendingPublishEntry;
import com.acme.enablements.commerce.workflows.PublishSide;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import com.acme.proto.acme.enablements.domain.enablements.v1.VoidChargeRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentChargeWorkflowTest {

    private static final String TASK_QUEUE = "payments";

    /**
     * Hand-written fake instead of a Mockito mock: Temporal's activity
     * registration rejects a Mockito-generated subclass because Byte Buddy
     * copies the interface's @ActivityMethod annotations onto the mock's
     * overriding methods, which Temporal's registration validator flags as
     * invalid ("this annotation can be used only on the interface method").
     */
    private static class RecordingRegistryActivities implements PendingPublishRegistryActivities {
        final List<String> authorizedOrderIds = new ArrayList<>();
        final List<String> capturedOrderIds = new ArrayList<>();

        @Override
        public void registerCommerceOrder(String orderId, DemoScenario scenario, String commercePayloadJson) {
        }

        @Override
        public void registerPaymentAuthorization(String orderId, String authorizedPayloadJson) {
            authorizedOrderIds.add(orderId);
        }

        @Override
        public void registerPaymentCapture(String orderId, String paymentPayloadJson) {
            capturedOrderIds.add(orderId);
        }

        @Override
        public void markDelivered(String orderId, PublishSide side) {
        }

        @Override
        public List<PendingPublishEntry> getPendingEntries() {
            return List.of();
        }
    }

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;
    private RecordingRegistryActivities registryActivities;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(PaymentChargeImpl.class);
        registryActivities = new RecordingRegistryActivities();
        worker.registerActivitiesImplementations(registryActivities);
        testEnv.start();
        client = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private PaymentCharge newStub(String chargeId) {
        return client.newWorkflowStub(
                PaymentCharge.class,
                WorkflowOptions.newBuilder().setWorkflowId(chargeId).setTaskQueue(TASK_QUEUE).build());
    }

    @Test
    void approvedCardAuthorizesThenAutoCaptures() throws Exception {
        var stub = newStub("charge-approved");
        var request = CreateChargeRequest.newBuilder()
                .setOrderId("order-1").setCustomerId("cust-1").setAmountCents(1000)
                .setCardNumber("4242424242424242").build();

        WorkflowClient.execute(stub::execute, request).get(30, TimeUnit.SECONDS);

        assertThat(stub.getState().getStatus()).isEqualTo("CAPTURED");
        assertThat(registryActivities.authorizedOrderIds).containsExactly("order-1");
        assertThat(registryActivities.capturedOrderIds).containsExactly("order-1");
    }

    @Test
    void declinedCardNeverRegistersAuthorization() throws Exception {
        var stub = newStub("charge-declined");
        var request = CreateChargeRequest.newBuilder()
                .setOrderId("order-2").setCustomerId("cust-1").setAmountCents(1000)
                .setCardNumber("4000000000000002").build();

        WorkflowClient.execute(stub::execute, request).get(30, TimeUnit.SECONDS);

        assertThat(stub.getState().getStatus()).isEqualTo("DECLINED");
        assertThat(registryActivities.authorizedOrderIds).isEmpty();
        assertThat(registryActivities.capturedOrderIds).isEmpty();
    }

    @Test
    void captureFailsCardAuthorizesButNeverRegistersCapture() throws Exception {
        var stub = newStub("charge-capture-fails");
        var request = CreateChargeRequest.newBuilder()
                .setOrderId("order-3").setCustomerId("cust-1").setAmountCents(1000)
                .setCardNumber("4000000000000259").build();

        WorkflowClient.execute(stub::execute, request).get(30, TimeUnit.SECONDS);

        assertThat(stub.getState().getStatus()).isEqualTo("CAPTURE_FAILED");
        assertThat(registryActivities.authorizedOrderIds).containsExactly("order-3");
        assertThat(registryActivities.capturedOrderIds).isEmpty();
    }

    @Test
    void voidBeforeCaptureWinsAndRegistersNoCapture() throws Exception {
        var stub = newStub("charge-voided");
        var request = CreateChargeRequest.newBuilder()
                .setOrderId("order-4").setCustomerId("cust-1").setAmountCents(1000)
                .setCardNumber("4242424242424242").build();

        WorkflowStub.fromTyped(stub).start(request);
        stub.voidCharge(VoidChargeRequest.newBuilder().setChargeId("charge-voided").build());
        WorkflowStub.fromTyped(stub).getResult(30, TimeUnit.SECONDS, Void.class);

        assertThat(stub.getState().getStatus()).isEqualTo("VOIDED");
        assertThat(registryActivities.authorizedOrderIds).containsExactly("order-4");
        assertThat(registryActivities.capturedOrderIds).isEmpty();
    }
}
