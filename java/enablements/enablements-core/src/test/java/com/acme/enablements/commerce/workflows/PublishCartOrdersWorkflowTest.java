package com.acme.enablements.commerce.workflows;

import com.acme.enablements.activities.PendingPublishRegistryActivities;
import com.acme.enablements.activities.WebhookPublisher;
import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PublishCartOrdersWorkflowTest {

    private static final String TASK_QUEUE = "commerce";

    /** See PaymentChargeWorkflowTest for why this is a hand-written fake, not a Mockito mock. */
    private static class RecordingRegistryActivities implements PendingPublishRegistryActivities {
        List<PendingPublishEntry> pendingEntries = List.of();
        final List<PublishSide> deliveredSides = new ArrayList<>();

        @Override
        public void registerCommerceOrder(String orderId, DemoScenario scenario, String commercePayloadJson) {
        }

        @Override
        public void registerPaymentAuthorization(String orderId, String authorizedPayloadJson) {
        }

        @Override
        public void registerPaymentCapture(String orderId, String paymentPayloadJson) {
        }

        @Override
        public void markDelivered(String orderId, PublishSide side) {
            deliveredSides.add(side);
        }

        @Override
        public List<PendingPublishEntry> getPendingEntries() {
            return pendingEntries;
        }
    }

    private static class RecordingWebhookPublisher implements WebhookPublisher {
        final List<String> publishedEventTypes = new ArrayList<>();

        @Override
        public void publish(String eventType, String payloadJson) {
            publishedEventTypes.add(eventType);
        }
    }

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;
    private RecordingRegistryActivities registry;
    private RecordingWebhookPublisher webhookPublisher;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(PublishCartOrdersImpl.class);
        registry = new RecordingRegistryActivities();
        webhookPublisher = new RecordingWebhookPublisher();
        worker.registerActivitiesImplementations(registry, webhookPublisher);
        testEnv.start();
        client = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private void runOnce(int n) throws Exception {
        var stub = client.newWorkflowStub(
                PublishCartOrders.class,
                WorkflowOptions.newBuilder().setWorkflowId("publish-run-" + n).setTaskQueue(TASK_QUEUE).build());
        WorkflowClient.execute(stub::execute).get(30, TimeUnit.SECONDS);
    }

    @Test
    void normalScenarioDeliversBothSides() throws Exception {
        registry.pendingEntries = List.of(new PendingPublishEntry("order-normal", DemoScenario.NORMAL,
                Optional.empty(), false, Optional.of("commerce-payload"), Optional.of("payment-payload"), false, false));

        runOnce(1);

        assertThat(webhookPublisher.publishedEventTypes).containsExactlyInAnyOrder("commerce.order.submitted", "payment.captured");
        assertThat(registry.deliveredSides).containsExactlyInAnyOrder(PublishSide.COMMERCE, PublishSide.PAYMENT);
    }

    @Test
    void paymentBeforeCommerceWithholdsCommerceUntilPaymentAlreadyDelivered() throws Exception {
        registry.pendingEntries = List.of(new PendingPublishEntry("order-reorder", DemoScenario.PAYMENT_BEFORE_COMMERCE,
                Optional.empty(), false, Optional.of("commerce-payload"), Optional.of("payment-payload"), false, false));

        runOnce(2);

        assertThat(webhookPublisher.publishedEventTypes).containsExactly("payment.captured");
        assertThat(registry.deliveredSides).containsExactly(PublishSide.PAYMENT);
    }

    @Test
    void paymentBeforeCommerceDeliversCommerceOnceAlreadyPaymentDelivered() throws Exception {
        registry.pendingEntries = List.of(new PendingPublishEntry("order-reorder2", DemoScenario.PAYMENT_BEFORE_COMMERCE,
                Optional.empty(), false, Optional.of("commerce-payload"), Optional.of("payment-payload"), false, true));

        runOnce(3);

        assertThat(webhookPublisher.publishedEventTypes).containsExactly("commerce.order.submitted");
        assertThat(registry.deliveredSides).containsExactly(PublishSide.COMMERCE);
    }

    @Test
    void missingCommerceEventNeverAttemptsCommerceSide() throws Exception {
        registry.pendingEntries = List.of(new PendingPublishEntry("order-missing-commerce", DemoScenario.MISSING_COMMERCE_EVENT,
                Optional.empty(), false, Optional.of("commerce-payload"), Optional.of("payment-payload"), false, false));

        runOnce(4);

        assertThat(webhookPublisher.publishedEventTypes).containsExactly("payment.captured");
        assertThat(registry.deliveredSides).containsExactly(PublishSide.PAYMENT);
    }

    @Test
    void missingPaymentEventNeverAttemptsPaymentSide() throws Exception {
        registry.pendingEntries = List.of(new PendingPublishEntry("order-missing-payment", DemoScenario.MISSING_PAYMENT_EVENT,
                Optional.empty(), false, Optional.of("commerce-payload"), Optional.of("payment-payload"), false, false));

        runOnce(5);

        assertThat(webhookPublisher.publishedEventTypes).containsExactly("commerce.order.submitted");
        assertThat(registry.deliveredSides).containsExactly(PublishSide.COMMERCE);
    }

    @Test
    void authorizedSideDeliversUnconditionallyRegardlessOfScenario() throws Exception {
        registry.pendingEntries = List.of(new PendingPublishEntry("order-auth", DemoScenario.MISSING_PAYMENT_EVENT,
                Optional.of("authorized-payload"), false, Optional.empty(), Optional.empty(), false, false));

        runOnce(6);

        assertThat(webhookPublisher.publishedEventTypes).containsExactly("payment.authorized");
        assertThat(registry.deliveredSides).containsExactly(PublishSide.AUTHORIZED);
    }
}
