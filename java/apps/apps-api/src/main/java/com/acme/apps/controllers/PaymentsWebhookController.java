package com.acme.apps.controllers;

import com.acme.apps.workflows.Order;
import com.acme.proto.acme.apps.api.orders.v1.MakePaymentRequest;
import com.acme.proto.acme.apps.api.orders.v1.MakePaymentResponse;
import com.acme.proto.acme.apps.domain.apps.v1.CapturePaymentRequest;
import com.acme.proto.acme.apps.domain.apps.v1.CompleteOrderRequest;
import com.acme.proto.acme.apps.domain.apps.v1.GetCompleteOrderStateResponse;
import com.acme.proto.acme.common.v1.Money;
import com.acme.proto.acme.oms.v1.Payment;
import com.google.protobuf.Timestamp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Payments App Webhook Controller
 *
 * Receives webhooks from external payments application (Stripe)
 * Uses Update to send payment data to CompleteOrder workflow
 *
 * URI Template: POST /api/v1/payments-app/orders
 */
@RestController
@RequestMapping("/api/v1/payments-app")
@Tag(name = "Payments Webhooks", description = "Webhook endpoints for payments app integration")
//@SecurityRequirement(name = "ApiKeyAuth")
public class PaymentsWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentsWebhookController.class);

    private final WorkflowClient workflowClient;

    public PaymentsWebhookController(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    /**
     * Submit payment data
     *
     * URI Template: POST /api/v1/payments-app/orders
     */
    @PostMapping("/orders")
    @Operation(
        summary = "Submit payment data",
        description = "Receives payment data from Stripe webhook and sends to CompleteOrder workflow via Update"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Payment data accepted",
            content = @Content(schema = @Schema(implementation = MakePaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        @ApiResponse(responseCode = "409", description = "Payment data already submitted for this order")
    })
    public ResponseEntity<MakePaymentResponse> submitPaymentOrder(
            @RequestBody MakePaymentRequest request) {

        logger.info("Received payment for order: {}", request.getMetadata().getOrderId());

        String orderId = request.getMetadata().getOrderId();

        try {
            // Prepare update request using protobuf builders
            var updateRequest = CapturePaymentRequest.newBuilder()
                    .setPayment(Payment.newBuilder().setRrn(request.getRrn())
                            .setAmount(Money.newBuilder().setCurrency("US").setUnits(request.getAmountCents())).build())
                .build();

            // Prepare workflow start request
            Instant now = Instant.now();
            var workflowRequest = CompleteOrderRequest.newBuilder()
                .setOrderId(orderId)
                .setTimestamp(Timestamp.newBuilder()
                    .setSeconds(now.getEpochSecond())
                    .setNanos(now.getNano())
                    .build())
                .build();

            // Get workflow stub for method references
            Order workflowStub = workflowClient.newWorkflowStub(
                Order.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(orderId)
                    .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                    .setTaskQueue("apps")
                    .build()
            );

            // StartUpdateWithStart:  start workflow and execute update in one operation
            WorkflowClient.startUpdateWithStart(
                workflowStub::capturePayment,
                updateRequest,
                UpdateOptions.<GetCompleteOrderStateResponse>newBuilder()
                        .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                        .build(),
                new WithStartWorkflowOperation<>(
                    workflowStub::execute,
                    workflowRequest
                )
            );

            logger.info("Payment submitted successfully for orderId: {}", orderId);

            var response = MakePaymentResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus("accepted")
                .build();

            return ResponseEntity.accepted().body(response);

        } catch (WorkflowUpdateException e) {
            logger.error("Failed to submit payment: {}", e.getMessage(), e);
            var errorResponse = MakePaymentResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus("failed")
                .build();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
        } catch (Exception e) {
            logger.error("Unexpected error submitting payment: {}", e.getMessage(), e);
            var errorResponse = MakePaymentResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus("error")
                .build();
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }
}