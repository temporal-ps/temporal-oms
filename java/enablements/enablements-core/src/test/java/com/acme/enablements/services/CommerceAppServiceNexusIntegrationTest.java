package com.acme.enablements.services;

import com.acme.enablements.activities.CommerceAppImpl;
import com.acme.enablements.integrations.EnablementsIntegrationsClient;
import com.acme.oms.services.CommerceAppService;
import com.acme.proto.acme.oms.v1.Order;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderRequest;
import com.acme.proto.acme.processing.domain.processing.v1.ValidateOrderResponse;
import com.google.protobuf.util.Durations;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import io.temporal.api.nexus.v1.EndpointTarget;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest;
import io.temporal.api.operatorservice.v1.DeleteNexusEndpointRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.ListWorkflowExecutionsRequest;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.OperatorServiceStubs;
import io.temporal.serviceclient.OperatorServiceStubsOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the {@code validateOrder} Nexus operation end to end: a caller workflow invokes the
 * operation, the handler starts a standalone {@code CommerceApp} activity, and the result returns
 * through the Nexus callback.
 *
 * <p>Requires a Temporal server with standalone activities enabled, such as one started by
 * {@code scripts/start-temporal-dev.sh}. The in-memory test server does not implement
 * {@code StartActivityExecution}. The test is skipped when no server answers at
 * {@code TEMPORAL_IT_ADDRESS} (default {@code localhost:7233}). It uses its own namespace so the
 * {@code integrations} and {@code commerce-app} task queues do not collide with a running
 * enablements worker.
 */
class CommerceAppServiceNexusIntegrationTest {

    private static final String NAMESPACE = "enablements-it";
    private static final String HANDLER_TASK_QUEUE = "integrations";
    private static final String ACTIVITY_TASK_QUEUE = "commerce-app";
    private static final String CALLER_TASK_QUEUE = "commerce-app-it-caller";

    private static WorkflowServiceStubs serviceStubs;
    private static OperatorServiceStubs operatorStubs;
    private static WorkerFactory workerFactory;
    private static WorkflowClient client;
    private static EnablementsIntegrationsClient integrationsClient;
    private static String endpointName;
    private static Endpoint endpoint;

    @WorkflowInterface
    public interface ValidateOrderCaller {
        @WorkflowMethod
        ValidateOrderResponse validate(String endpoint, ValidateOrderRequest request);
    }

    public static class ValidateOrderCallerImpl implements ValidateOrderCaller {
        @Override
        public ValidateOrderResponse validate(String endpoint, ValidateOrderRequest request) {
            var service = Workflow.newNexusServiceStub(CommerceAppService.class,
                    NexusServiceOptions.newBuilder()
                            .setEndpoint(endpoint)
                            .setOperationOptions(NexusOperationOptions.newBuilder()
                                    .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                                    .build())
                            .build());
            return service.validateOrder(request);
        }
    }

    @BeforeAll
    static void setUp() {
        var address = System.getenv().getOrDefault("TEMPORAL_IT_ADDRESS", "localhost:7233");
        serviceStubs = WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder().setTarget(address).build());
        try {
            serviceStubs.healthCheck();
        } catch (RuntimeException e) {
            serviceStubs.shutdownNow();
            serviceStubs = null;
            assumeTrue(false, "No Temporal server at " + address + ": " + e.getMessage());
        }
        operatorStubs = OperatorServiceStubs.newServiceStubs(OperatorServiceStubsOptions.newBuilder()
                .setChannel(serviceStubs.getRawChannel())
                .validateAndBuildWithDefaults());

        ensureNamespace();

        endpointName = "commerce-app-it-" + UUID.randomUUID().toString().substring(0, 8);
        endpoint = operatorStubs.blockingStub().createNexusEndpoint(CreateNexusEndpointRequest.newBuilder()
                        .setSpec(EndpointSpec.newBuilder()
                                .setName(endpointName)
                                .setTarget(EndpointTarget.newBuilder()
                                        .setWorker(EndpointTarget.Worker.newBuilder()
                                                .setNamespace(NAMESPACE)
                                                .setTaskQueue(HANDLER_TASK_QUEUE))))
                        .build())
                .getEndpoint();

        integrationsClient = mock(EnablementsIntegrationsClient.class);
        client = WorkflowClient.newInstance(serviceStubs,
                WorkflowClientOptions.newBuilder().setNamespace(NAMESPACE).build());
        workerFactory = WorkerFactory.newInstance(client);
        workerFactory.newWorker(HANDLER_TASK_QUEUE)
                .registerNexusServiceImplementation(new CommerceAppServiceImpl());
        workerFactory.newWorker(ACTIVITY_TASK_QUEUE)
                .registerActivitiesImplementations(new CommerceAppImpl(integrationsClient));
        workerFactory.newWorker(CALLER_TASK_QUEUE)
                .registerWorkflowImplementationTypes(ValidateOrderCallerImpl.class);
        workerFactory.start();
    }

    @AfterAll
    static void tearDown() {
        if (workerFactory != null) {
            workerFactory.shutdownNow();
        }
        if (endpoint != null) {
            operatorStubs.blockingStub().deleteNexusEndpoint(DeleteNexusEndpointRequest.newBuilder()
                    .setId(endpoint.getId())
                    .setVersion(endpoint.getVersion())
                    .build());
        }
        if (operatorStubs != null) {
            operatorStubs.shutdownNow();
        }
        if (serviceStubs != null) {
            serviceStubs.shutdownNow();
        }
    }

    @Test
    void validateOrderRunsAsStandaloneActivityThroughNexus() {
        var request = ValidateOrderRequest.newBuilder()
                .setOrder(Order.newBuilder().setOrderId("order-it-1").build())
                .build();
        var expected = ValidateOrderResponse.newBuilder()
                .setOrder(request.getOrder())
                .setManualCorrectionNeeded(true)
                .build();
        when(integrationsClient.validateOrder(request)).thenReturn(expected);

        var caller = client.newWorkflowStub(ValidateOrderCaller.class, WorkflowOptions.newBuilder()
                .setWorkflowId("commerce-app-it-" + UUID.randomUUID())
                .setTaskQueue(CALLER_TASK_QUEUE)
                .setWorkflowExecutionTimeout(Duration.ofSeconds(60))
                .build());

        var response = caller.validate(endpointName, request);

        assertThat(response).isEqualTo(expected);
        verify(integrationsClient).validateOrder(request);
    }

    /** Registers {@link #NAMESPACE} if missing and waits until the frontend serves requests for it. */
    private static void ensureNamespace() {
        var stub = serviceStubs.blockingStub();
        try {
            stub.describeNamespace(DescribeNamespaceRequest.newBuilder().setNamespace(NAMESPACE).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
                throw e;
            }
            stub.registerNamespace(RegisterNamespaceRequest.newBuilder()
                    .setNamespace(NAMESPACE)
                    .setWorkflowExecutionRetentionPeriod(Durations.fromDays(1))
                    .build());
        }
        var deadline = Instant.now().plusSeconds(30);
        while (true) {
            try {
                stub.listWorkflowExecutions(ListWorkflowExecutionsRequest.newBuilder()
                        .setNamespace(NAMESPACE)
                        .setPageSize(1)
                        .build());
                return;
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() != Status.Code.NOT_FOUND || Instant.now().isAfter(deadline)) {
                    throw e;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
