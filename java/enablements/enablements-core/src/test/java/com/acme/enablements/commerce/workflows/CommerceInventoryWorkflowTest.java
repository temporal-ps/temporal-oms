package com.acme.enablements.commerce.workflows;

import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceInventoryState;
import com.acme.proto.acme.enablements.domain.enablements.v1.HoldInventoryRequest;
import com.acme.proto.acme.oms.v1.Item;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceInventoryWorkflowTest {

    private static final String TASK_QUEUE = "commerce";

    private TestWorkflowEnvironment testEnv;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        testEnv = TestWorkflowEnvironment.newInstance();
        Worker worker = testEnv.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(CommerceInventoryImpl.class);
        testEnv.start();
        client = testEnv.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        testEnv.close();
    }

    private CommerceInventory newStub(String workflowId) {
        return client.newWorkflowStub(
                CommerceInventory.class,
                WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(TASK_QUEUE).build());
    }

    @Test
    void holdDecrementsStock() {
        var stub = newStub("inventory-hold");
        var initial = CommerceInventoryState.newBuilder().putStockByItemId("item-1", 10).build();
        WorkflowClient.start(stub::execute, initial);

        stub.hold(HoldInventoryRequest.newBuilder()
                .addItems(Item.newBuilder().setItemId("item-1").setQuantity(3).build())
                .build());

        assertThat(stub.getState().getStockByItemIdMap()).containsEntry("item-1", 7);
    }

    @Test
    void holdRejectsOverHold() {
        var stub = newStub("inventory-overhold");
        var initial = CommerceInventoryState.newBuilder().putStockByItemId("item-1", 2).build();
        WorkflowClient.start(stub::execute, initial);

        assertThatThrownBy(() -> stub.hold(HoldInventoryRequest.newBuilder()
                .addItems(Item.newBuilder().setItemId("item-1").setQuantity(5).build())
                .build()))
                .isInstanceOf(Exception.class);

        assertThat(stub.getState().getStockByItemIdMap()).containsEntry("item-1", 2);
    }

    @Test
    void releaseIncrementsStockBack() {
        var stub = newStub("inventory-release");
        var initial = CommerceInventoryState.newBuilder().putStockByItemId("item-1", 5).build();
        WorkflowClient.start(stub::execute, initial);

        var request = HoldInventoryRequest.newBuilder()
                .addItems(Item.newBuilder().setItemId("item-1").setQuantity(2).build())
                .build();
        stub.hold(request);
        stub.release(request);

        assertThat(stub.getState().getStockByItemIdMap()).containsEntry("item-1", 5);
    }
}
