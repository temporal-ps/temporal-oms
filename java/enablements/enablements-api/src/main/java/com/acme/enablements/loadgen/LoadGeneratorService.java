package com.acme.enablements.loadgen;

import com.acme.enablements.workflows.WorkerVersionEnablement;
import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import com.acme.proto.acme.enablements.v1.WorkerVersionEnablementState;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.Instant;

@Service
public class LoadGeneratorService {

    private static final Logger logger = LoggerFactory.getLogger(LoadGeneratorService.class);
    private static final DateTimeFormatter ID_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .withZone(ZoneOffset.UTC);

    private final WorkflowClient workflowClient;

    public LoadGeneratorService(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public WorkerVersionEnablementState start(StartWorkerVersionEnablementRequest request) {
        String enablementId = request.getEnablementId().isBlank()
                ? "load-" + ID_TIMESTAMP.format(Instant.now())
                : request.getEnablementId();
        StartWorkerVersionEnablementRequest requestWithId = request.toBuilder()
                .setEnablementId(enablementId)
                .build();

        logger.info("Starting load generator {}", enablementId);

        WorkerVersionEnablement stub = workflowClient.newWorkflowStub(
                WorkerVersionEnablement.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(enablementId)
                        .setTaskQueue("enablements")
                        .build());
        WorkflowClient.start(stub::execute, requestWithId);
        return stub.getState();
    }

    public void pause(String enablementId) {
        workflowClient.newWorkflowStub(WorkerVersionEnablement.class, enablementId).pause();
    }

    public void resume(String enablementId) {
        workflowClient.newWorkflowStub(WorkerVersionEnablement.class, enablementId).resume();
    }

    public void stop(String enablementId, String reason) {
        workflowClient.newUntypedWorkflowStub(enablementId).terminate(reason);
    }

    public WorkerVersionEnablementState getState(String enablementId) {
        return workflowClient.newWorkflowStub(WorkerVersionEnablement.class, enablementId).getState();
    }
}
