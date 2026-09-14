package com.acme.apps.controllers;

import com.acme.apps.workflows.Order;
import com.acme.proto.acme.apps.api.orders.v1.*;
import com.google.protobuf.Timestamp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

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

    private final WorkflowClient workflowClient;

    public CommerceWebhookController(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
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
            @RequestBody SubmitOrderRequest request) {

        logger.info("Received commerce order for orderId: {}", orderId);

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

            var updateRequest = com.acme.proto.acme.apps.domain.apps.v1.SubmitOrderRequest.newBuilder()
                .setOrder(domainOrderBuilder.build())
                .build();

            // Prepare workflow start request
            Instant now = Instant.now();
            var completeOrderRequest = com.acme.proto.acme.apps.domain.apps.v1.CompleteOrderRequest.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(request.getCustomerId())
                .setTimestamp(Timestamp.newBuilder()
                    .setSeconds(now.getEpochSecond())
                    .setNanos(now.getNano())
                    .build())
                .build();

            // Get workflow stub for method references
            Order workflow = workflowClient.newWorkflowStub(
                Order.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(orderId)
                        .setWorkflowIdConflictPolicy(WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE_FAILED_ONLY)
                    .setTaskQueue("apps")
                    .build()
            );

            // StartUpdateWithStart: atomically start workflow and execute update in one operation
            WorkflowClient.startUpdateWithStart(
                workflow::submitOrder,
                updateRequest,
                UpdateOptions.<com.acme.proto.acme.apps.domain.apps.v1.SubmitOrderRequest>newBuilder()
                        .setWaitForStage(WorkflowUpdateStage.ACCEPTED)
                        .build(),
                new WithStartWorkflowOperation<>(
                    workflow::execute,
                    completeOrderRequest
                )
            );

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