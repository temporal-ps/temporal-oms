package com.acme.enablements;

import com.acme.enablements.commerce.CommerceCatalogFixtureService;
import com.acme.enablements.commerce.workflows.CommerceInventory;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceInventoryState;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Starts the CommerceInventory singleton at worker startup, seeded with the
 * catalog fixture's initial_stock, so it exists before any order can hold
 * against it.
 */
@Component
public class CommerceInventorySeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(CommerceInventorySeeder.class);

    private final WorkflowClient workflowClient;
    private final CommerceCatalogFixtureService catalogFixture;

    public CommerceInventorySeeder(WorkflowClient workflowClient, CommerceCatalogFixtureService catalogFixture) {
        this.workflowClient = workflowClient;
        this.catalogFixture = catalogFixture;
    }

    @Override
    public void run(ApplicationArguments args) {
        var stateBuilder = CommerceInventoryState.newBuilder();
        for (var item : catalogFixture.fixture().items()) {
            stateBuilder.putStockByItemId(item.itemId(), item.initialStock());
        }

        var stub = workflowClient.newWorkflowStub(
                CommerceInventory.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(CommerceInventory.WORKFLOW_ID)
                        .setTaskQueue("commerce")
                        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                        .build());
        WorkflowClient.start(stub::execute, stateBuilder.build());
        logger.info("CommerceInventory seeded/verified with {} catalog item(s)", catalogFixture.fixture().items().size());
    }
}
