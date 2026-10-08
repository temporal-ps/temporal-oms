package com.acme.apps.controllers;

import com.acme.oms.services.AppsService;
import com.acme.proto.acme.apps.api.orders.v1.SubmitOrderResponse;
import com.acme.proto.acme.enablements.domain.enablements.v1.CommerceOrderEvent;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;

/**
 * Commerce App Webhook Controller
 *
 * Receives CommerceOrderEvent webhooks from the external commerce application and
 * forwards each as a standalone Nexus operation (AppsService.submitOrder) keyed by event ID.
 *
 * URI Template: /api/v1/commerce-app/orders/{orderId}
 */
@RestController
@RequestMapping("/api/v1/commerce-app")
@Tag(name = "Commerce Webhooks", description = "Webhook endpoints for commerce app integration")
//@SecurityRequirement(name = "ApiKeyAuth")
public class CommerceWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(CommerceWebhookController.class);

    private final NexusServiceClient<AppsService> appsService;

    public CommerceWebhookController(
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
     * Handle a CommerceOrderEvent
     *
     * URI Template: PUT /api/v1/commerce-app/orders/{orderId}
     */
    @PutMapping("/orders/{orderId}")
    @Operation(
        summary = "Handle commerce order event",
        description = "Receives a CommerceOrderEvent from the external commerce app and starts AppsService.submitOrder, deduplicated by event ID"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Order data accepted",
            content = @Content(schema = @Schema(implementation = SubmitOrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid API key")
    })
    public ResponseEntity<SubmitOrderResponse> handleCommerceOrderEvent(
            @Parameter(description = "Order ID", required = true)
            @PathVariable String orderId,
            @RequestBody CommerceOrderEvent event) {

        logger.info("Received {} event {} for orderId: {}", event.getType(), event.getEventId(), orderId);
        if (event.getEventId().isBlank() || !event.hasOrder()) {
            return ResponseEntity.badRequest().body(submitOrderResponse(orderId, "invalid"));
        }

        try {
            var order = event.getOrder();
            var domainOrderBuilder = com.acme.proto.acme.oms.v1.Order.newBuilder()
                .setOrderId(order.getOrderId())
                .addAllItems(order.getItemsList())
                .setShippingAddress(order.getShippingAddress());
            if (order.hasSelectedShipment()) {
                domainOrderBuilder.setSelectedShipment(order.getSelectedShipment());
            }

            Instant now = Instant.now();
            var timestamp = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
            var completeOrderRequest = com.acme.proto.acme.apps.domain.apps.v1.CompleteOrderRequest.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(order.getCustomerId())
                .setTimestamp(timestamp)
                .build();
            var submitOrderRequest = com.acme.proto.acme.apps.domain.apps.v1.SubmitOrderRequest.newBuilder()
                .setTimestamp(timestamp)
                .setOrderId(orderId)
                .setOrder(domainOrderBuilder.build())
                .setCompleteOrderRequest(completeOrderRequest)
                .build();

            // The event ID is the standalone operation ID, so the server deduplicates redelivered events.
            try {
                appsService.start(
                    AppsService::submitOrder,
                    StartNexusOperationOptions.newBuilder()
                        .setId(event.getEventId())
                        .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                        .setIdConflictPolicy(NexusOperationIdConflictPolicy.NEXUS_OPERATION_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setIdReusePolicy(NexusOperationIdReusePolicy.NEXUS_OPERATION_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build(),
                    submitOrderRequest);
            } catch (NexusOperationAlreadyStartedException e) {
                logger.info("Event {} already delivered for orderId: {}", event.getEventId(), orderId);
            }

            logger.info("Commerce order submitted successfully for orderId: {}", orderId);
            return ResponseEntity.accepted().body(submitOrderResponse(orderId, "accepted"));

        } catch (Exception e) {
            logger.error("Unexpected error submitting commerce order: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(submitOrderResponse(orderId, "error"));
        }
    }

    private static SubmitOrderResponse submitOrderResponse(String orderId, String status) {
        Instant now = Instant.now();
        return SubmitOrderResponse.newBuilder()
            .setOrderId(orderId)
            .setStatus(status)
            .setCreatedAt(Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build())
            .build();
    }
}
