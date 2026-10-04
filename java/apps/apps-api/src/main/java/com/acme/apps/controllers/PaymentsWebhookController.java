package com.acme.apps.controllers;

import com.acme.oms.services.AppsService;
import com.acme.proto.acme.apps.api.orders.v1.MakePaymentRequest;
import com.acme.proto.acme.apps.api.orders.v1.MakePaymentResponse;
import com.acme.proto.acme.apps.domain.apps.v1.CapturePaymentRequest;
import com.acme.proto.acme.apps.domain.apps.v1.CompleteOrderRequest;
import com.acme.proto.acme.common.v1.Money;
import com.acme.proto.acme.oms.v1.Payment;
import com.google.protobuf.Timestamp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.temporal.api.enums.v1.NexusOperationIdConflictPolicy;
import io.temporal.api.enums.v1.NexusOperationIdReusePolicy;
import io.temporal.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

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

    private final NexusServiceClient<AppsService> appsService;

    public PaymentsWebhookController(
            WorkflowClient workflowClient,
            @Value("${oms.apps.nexus.endpoints.apps}") String appsEndpoint) {
        var nexusClient = NexusClient.newInstance(
                workflowClient.getWorkflowServiceStubs(),
                NexusClientOptions.newBuilder()
                        .setNamespace(workflowClient.getOptions().getNamespace())
                        .setDataConverter(workflowClient.getOptions().getDataConverter())
                        .build());
        this.appsService = nexusClient.newNexusServiceClient(AppsService.class, appsEndpoint);
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
            @Parameter(description = "Idempotency key; a retry with the same value is applied once")
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody MakePaymentRequest request) {

        String orderId = request.getMetadata().getOrderId();

        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            logger.warn("No X-Request-Id header for orderId: {}; retries will not be deduplicated", orderId);
        }
        logger.info("Received payment for orderId: {} requestId: {}", orderId, requestId);

        try {
            Instant now = Instant.now();
            var timestamp = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
            var completeOrderRequest = CompleteOrderRequest.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(request.getCustomerId())
                .setTimestamp(timestamp)
                .build();
            var capturePaymentRequest = CapturePaymentRequest.newBuilder()
                .setTimestamp(timestamp)
                .setOrderId(orderId)
                .setPayment(Payment.newBuilder().setRrn(request.getRrn())
                        .setAmount(Money.newBuilder().setCurrency("US").setUnits(request.getAmountCents())).build())
                .setCompleteOrderRequest(completeOrderRequest)
                .build();

            // The request ID is the standalone operation ID, so the server deduplicates retried webhooks.
            try {
                appsService.start(
                    AppsService::capturePayment,
                    StartNexusOperationOptions.newBuilder()
                        .setId(requestId)
                        .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                        .setIdConflictPolicy(NexusOperationIdConflictPolicy.NEXUS_OPERATION_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setIdReusePolicy(NexusOperationIdReusePolicy.NEXUS_OPERATION_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build(),
                    capturePaymentRequest);
            } catch (NexusOperationAlreadyStartedException e) {
                logger.info("Payment already submitted for orderId: {} requestId: {}", orderId, requestId);
            }

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