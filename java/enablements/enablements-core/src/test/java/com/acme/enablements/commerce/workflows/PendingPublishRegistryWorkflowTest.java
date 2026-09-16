package com.acme.enablements.commerce.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.DemoScenario;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PendingPublishRegistryWorkflowTest {

    private static final String TASK_QUEUE = "commerce";

    private TestWorkflowEnvironment testEnv;
    private PendingPublishRegistry stub;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(PendingPublishRegistryImpl.class);
        testEnv.start();

        WorkflowClient client = testEnv.getWorkflowClient();
        stub = client.newWorkflowStub(
                PendingPublishRegistry.class,
                WorkflowOptions.newBuilder().setWorkflowId("registry-under-test").setTaskQueue(TASK_QUEUE).build());
        WorkflowClient.start(stub::execute, List.of());
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    @Test
    void registeringCommerceOrderCreatesAPendingEntry() {
        stub.registerCommerceOrder("order-1", DemoScenario.NORMAL, "{\"orderId\":\"order-1\"}");

        var entries = stub.getPendingEntries();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().orderId()).isEqualTo("order-1");
        assertThat(entries.getFirst().commercePayloadJson()).contains("{\"orderId\":\"order-1\"}");
        assertThat(entries.getFirst().commerceDelivered()).isFalse();
    }

    @Test
    void markDeliveredRemovesEntryFromPendingOnceEverySideIsDelivered() {
        stub.registerCommerceOrder("order-2", DemoScenario.NORMAL, "commerce-payload");
        stub.registerPaymentCapture("order-2", "payment-payload");

        assertThat(stub.getPendingEntries()).hasSize(1);

        stub.markDelivered("order-2", PublishSide.COMMERCE);
        assertThat(stub.getPendingEntries()).hasSize(1);

        stub.markDelivered("order-2", PublishSide.PAYMENT);
        assertThat(stub.getPendingEntries()).isEmpty();
    }

    @Test
    void missingCommerceEventStaysPendingForeverOnThePaymentSideAlone() {
        stub.registerPaymentCapture("order-3", "payment-payload");

        var entries = stub.getPendingEntries();
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().commercePayloadJson()).isEmpty();
        assertThat(entries.getFirst().paymentPayloadJson()).isPresent();
    }
}
