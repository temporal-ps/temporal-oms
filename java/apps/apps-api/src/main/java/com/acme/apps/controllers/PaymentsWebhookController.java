package com.acme.apps.controllers;

import com.acme.oms.services.AppsService;
import com.acme.proto.acme.apps.api.orders.v1.MakePaymentResponse;
import com.acme.proto.acme.apps.domain.apps.v1.CapturePaymentRequest;
import com.acme.proto.acme.apps.domain.apps.v1.CompleteOrderRequest;
import com.acme.proto.acme.common.v1.Money;
import com.acme.proto.acme.enablements.domain.enablements.v1.PaymentEvent;
import com.acme.proto.acme.oms.v1.Payment;
import com.google.protobuf.Timestamp;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;

/**
 * Payments App Webhook Controller
 *
 * Receives PaymentEvent webhooks from the external payments processor and forwards
 * payment.captured as a standalone Nexus operation (AppsService.capturePayment) keyed by event ID.
 *
 * URI Template: POST /api/v1/payments-app/orders
 */
@RestController
@RequestMapping("/api/v1/payments-app")
@Tag(name = "Payments Webhooks", description = "Webhook endpoints for payments app integration")
//@SecurityRequirement(name = "ApiKeyAuth")
public class PaymentsWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentsWebhookController.class);
    private static final String PAYMENT_CAPTURED = "payment.captured";

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
     * Handle a PaymentEvent
     *
     * URI Template: POST /api/v1/payments-app/orders
     */
    @PostMapping("/orders")
    @Operation(
        summary = "Handle payment event",
        description = "Receives a PaymentEvent from the payments processor; payment.captured starts AppsService.capturePayment, deduplicated by event ID"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Payment event accepted",
            content = @Content(schema = @Schema(implementation = MakePaymentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid API key")
    })
    public ResponseEntity<MakePaymentResponse> handlePaymentEvent(@RequestBody PaymentEvent event) {

        var charge = event.getCharge();
        String orderId = charge.getOrderId();
        logger.info("Received {} event {} for orderId: {}", event.getType(), event.getEventId(), orderId);
        if (event.getEventId().isBlank() || !event.hasCharge()) {
            return ResponseEntity.badRequest().body(makePaymentResponse(orderId, "invalid"));
        }
        if (!PAYMENT_CAPTURED.equals(event.getType())) {
            return ResponseEntity.accepted().body(makePaymentResponse(orderId, "ignored"));
        }

        try {
            Instant now = Instant.now();
            var timestamp = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
            var completeOrderRequest = CompleteOrderRequest.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(charge.getCustomerId())
                .setTimestamp(timestamp)
                .build();
            var capturePaymentRequest = CapturePaymentRequest.newBuilder()
                .setTimestamp(timestamp)
                .setOrderId(orderId)
                .setPayment(Payment.newBuilder().setRrn(charge.getChargeId())
                        .setAmount(Money.newBuilder().setCurrency("US").setUnits(charge.getAmountCents())).build())
                .setCompleteOrderRequest(completeOrderRequest)
                .build();

            // The event ID is the standalone operation ID, so the server deduplicates redelivered events.
            try {
                appsService.start(
                    AppsService::capturePayment,
                    StartNexusOperationOptions.newBuilder()
                        .setId(event.getEventId())
                        .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                        .setIdConflictPolicy(NexusOperationIdConflictPolicy.NEXUS_OPERATION_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setIdReusePolicy(NexusOperationIdReusePolicy.NEXUS_OPERATION_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build(),
                    capturePaymentRequest);
            } catch (NexusOperationAlreadyStartedException e) {
                logger.info("Event {} already delivered for orderId: {}", event.getEventId(), orderId);
            }

            logger.info("Payment submitted successfully for orderId: {}", orderId);
            return ResponseEntity.accepted().body(makePaymentResponse(orderId, "accepted"));

        } catch (Exception e) {
            logger.error("Unexpected error submitting payment: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(makePaymentResponse(orderId, "error"));
        }
    }

    private static MakePaymentResponse makePaymentResponse(String orderId, String status) {
        return MakePaymentResponse.newBuilder()
            .setOrderId(orderId)
            .setStatus(status)
            .build();
    }
}
