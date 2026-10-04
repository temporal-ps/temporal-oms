package com.acme.apps.controllers;

import com.acme.oms.services.AppsService;
import com.acme.proto.acme.apps.api.orders.v1.*;
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
 * Commerce App Webhook Controller
 *
 * Receives webhooks from external commerce application
 * Uses UpdateWithStart pattern to send commerce data to CompleteOrder workflow
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
     * Submit commerce order data
     *
     * URI Template: PUT /api/v1/commerce-app/orders/{orderId}
     */
    @PutMapping("/orders/{orderId}")
    @Operation(
        summary = "Submit commerce order",
        description = "Receives commerce order data from external commerce app and sends to CompleteOrder workflow using UpdateWithStart"
    )
    @ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Order data accepted",
            content = @Content(schema = @Schema(implementation = SubmitOrderResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request body"),
        @ApiResponse(responseCode = "401", description = "Missing or invalid API key"),
        @ApiResponse(responseCode = "409", description = "Commerce data already submitted for this order")
    })
    public ResponseEntity<SubmitOrderResponse> submitCommerceOrder(
            @Parameter(description = "Order ID", required = true)
            @PathVariable String orderId,
            @Parameter(description = "Idempotency key; a retry with the same value is applied once")
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            @RequestBody SubmitOrderRequest request) {

        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            logger.warn("No X-Request-Id header for orderId: {}; retries will not be deduplicated", orderId);
        }
        logger.info("Received commerce order for orderId: {} requestId: {}", orderId, requestId);

        try {
            // Prepare update request using protobuf builders
            var domainOrderBuilder = com.acme.proto.acme.oms.v1.Order.newBuilder()
                .setOrderId(request.getOrder().getOrderId())
                .addAllItems(request.getOrder().getItemsList().stream()
                    .map(item -> com.acme.proto.acme.oms.v1.Item.newBuilder()
                        .setItemId(item.getItemId())
                        .setQuantity(item.getQuantity())
                        .build())
                    .toList())
                .setShippingAddress(
                    com.acme.proto.acme.common.v1.Address.newBuilder()
                        .setEasypost(com.acme.proto.acme.common.v1.EasyPostAddress.newBuilder()
                            .setStreet1(request.getOrder().getShippingAddress().getStreet())
                            .setCity(request.getOrder().getShippingAddress().getCity())
                            .setState(request.getOrder().getShippingAddress().getState())
                            .setZip(request.getOrder().getShippingAddress().getPostalCode())
                            .setCountry(request.getOrder().getShippingAddress().getCountry()))
                        .build());

            if (request.getOrder().hasSelectedShipment()) {
                var s = request.getOrder().getSelectedShipment();
                var shipmentBuilder = com.acme.proto.acme.common.v1.Shipment.newBuilder();
                if (s.getPaidPriceCents() > 0) {
                    shipmentBuilder.setPaidPrice(com.acme.proto.acme.common.v1.Money.newBuilder()
                        .setUnits(s.getPaidPriceCents())
                        .setCurrency(s.getCurrency().isBlank() ? "USD" : s.getCurrency())
                        .build());
                }
                if (s.hasDeliveryDays() || !s.getRateId().isBlank()) {
                    shipmentBuilder.setEasypost(com.acme.proto.acme.common.v1.EasyPostShipment.newBuilder()
                        .setSelectedRate(com.acme.proto.acme.common.v1.EasyPostRate.newBuilder()
                            .setDeliveryDays(s.getDeliveryDays())
                            .setRateId(s.getRateId())
                            .build())
                        .build());
                }
                domainOrderBuilder.setSelectedShipment(shipmentBuilder.build());
            }

            Instant now = Instant.now();
            var timestamp = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
            var completeOrderRequest = com.acme.proto.acme.apps.domain.apps.v1.CompleteOrderRequest.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(request.getCustomerId())
                .setTimestamp(timestamp)
                .build();
            var submitOrderRequest = com.acme.proto.acme.apps.domain.apps.v1.SubmitOrderRequest.newBuilder()
                .setTimestamp(timestamp)
                .setOrderId(orderId)
                .setOrder(domainOrderBuilder.build())
                .setCompleteOrderRequest(completeOrderRequest)
                .build();

            // The request ID is the standalone operation ID, so the server deduplicates retried webhooks.
            try {
                appsService.start(
                    AppsService::submitOrder,
                    StartNexusOperationOptions.newBuilder()
                        .setId(requestId)
                        .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                        .setIdConflictPolicy(NexusOperationIdConflictPolicy.NEXUS_OPERATION_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setIdReusePolicy(NexusOperationIdReusePolicy.NEXUS_OPERATION_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build(),
                    submitOrderRequest);
            } catch (NexusOperationAlreadyStartedException e) {
                logger.info("Commerce order already submitted for orderId: {} requestId: {}", orderId, requestId);
            }

            logger.info("Commerce order submitted successfully for orderId: {}", orderId);

            Instant responseTime = Instant.now();
            var response = SubmitOrderResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus("accepted")
                .setCreatedAt(Timestamp.newBuilder()
                    .setSeconds(responseTime.getEpochSecond())
                    .setNanos(responseTime.getNano())
                    .build())
                .build();

            return ResponseEntity.accepted().body(response);

        } catch (WorkflowUpdateException e) {
            logger.error("Failed to submit commerce order: {}", e.getMessage(), e);
            Instant errorTime = Instant.now();
            var errorResponse = SubmitOrderResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus("failed")
                .setCreatedAt(Timestamp.newBuilder()
                    .setSeconds(errorTime.getEpochSecond())
                    .setNanos(errorTime.getNano())
                    .build())
                .build();
            return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
        } catch (Exception e) {
            logger.error("Unexpected error submitting commerce order: {}", e.getMessage(), e);
            Instant errorTime = Instant.now();
            var errorResponse = SubmitOrderResponse.newBuilder()
                .setOrderId(orderId)
                .setStatus("error")
                .setCreatedAt(Timestamp.newBuilder()
                    .setSeconds(errorTime.getEpochSecond())
                    .setNanos(errorTime.getNano())
                    .build())
                .build();
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

}