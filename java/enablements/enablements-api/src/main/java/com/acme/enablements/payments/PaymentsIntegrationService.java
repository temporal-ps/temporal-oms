package com.acme.enablements.payments;

import com.acme.enablements.payments.workflows.PaymentCharge;
import com.acme.proto.acme.enablements.domain.enablements.v1.CaptureChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.CreateChargeRequest;
import com.acme.proto.acme.enablements.domain.enablements.v1.PaymentChargeState;
import com.acme.proto.acme.enablements.domain.enablements.v1.VoidChargeRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class PaymentsIntegrationService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentsIntegrationService.class);

    private final WorkflowClient workflowClient;

    public PaymentsIntegrationService(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public PaymentChargeState createCharge(CreateChargeRequest request) {
        String chargeId = "charge-" + UUID.randomUUID();
        logger.info("createCharge chargeId={}, orderId={}", chargeId, request.getOrderId());

        PaymentCharge stub = workflowClient.newWorkflowStub(
                PaymentCharge.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(chargeId)
                        .setTaskQueue("payments")
                        .build());
        WorkflowClient.start(stub::execute, request);
        return stub.getState();
    }

    public PaymentChargeState capture(String chargeId) {
        PaymentCharge stub = workflowClient.newWorkflowStub(PaymentCharge.class, chargeId);
        stub.capture(CaptureChargeRequest.newBuilder().setChargeId(chargeId).build());
        return stub.getState();
    }

    public PaymentChargeState voidCharge(String chargeId) {
        PaymentCharge stub = workflowClient.newWorkflowStub(PaymentCharge.class, chargeId);
        stub.voidCharge(VoidChargeRequest.newBuilder().setChargeId(chargeId).build());
        return stub.getState();
    }

    public PaymentChargeState getState(String chargeId) {
        PaymentCharge stub = workflowClient.newWorkflowStub(PaymentCharge.class, chargeId);
        return stub.getState();
    }
}
