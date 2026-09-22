package com.acme.enablements.deployment;

import com.acme.enablements.workflows.OmsVersionRollout;
import com.acme.proto.acme.enablements.v1.OmsVersionRolloutState;
import com.acme.proto.acme.enablements.v1.StartOmsVersionRolloutRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Starts and queries the {@link OmsVersionRollout} workflow that promotes
 * apps, processing, and fulfillment to a target OMS version (hosting.md
 * "OMS-Version-Driven Promotion"). Unlike {@link DeploymentService}, this
 * goes through a workflow, not a standalone activity: the rollout is a
 * multi-step, partial-failure-aware sequence, not a single call.
 */
@Service
public class OmsVersionRolloutService {

    private static final Logger logger = LoggerFactory.getLogger(OmsVersionRolloutService.class);
    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);
    private static final String TASK_QUEUE = "enablements";

    private final WorkflowClient workflowClient;

    public OmsVersionRolloutService(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public OmsVersionRolloutState start(String omsVersion) {
        String rolloutId = "oms-rollout-" + ID_TIMESTAMP.format(Instant.now());
        logger.info("Starting OMS version rollout {} to {}", rolloutId, omsVersion);

        OmsVersionRollout stub = workflowClient.newWorkflowStub(
                OmsVersionRollout.class,
                WorkflowOptions.newBuilder().setWorkflowId(rolloutId).setTaskQueue(TASK_QUEUE).build());

        StartOmsVersionRolloutRequest request = StartOmsVersionRolloutRequest.newBuilder()
                .setRolloutId(rolloutId)
                .setOmsVersion(omsVersion)
                .build();

        WorkflowClient.start(stub::execute, request);
        return getState(rolloutId);
    }

    public OmsVersionRolloutState getState(String rolloutId) {
        OmsVersionRollout stub = workflowClient.newWorkflowStub(OmsVersionRollout.class, rolloutId);
        return stub.getState();
    }
}
