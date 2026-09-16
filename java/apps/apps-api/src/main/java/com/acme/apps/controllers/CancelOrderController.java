package com.acme.apps.controllers;

import com.acme.apps.workflows.Order;
import com.acme.proto.acme.apps.domain.apps.v1.CancelOrderRequest;
import com.google.protobuf.Timestamp;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowUpdateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Cancels an in-flight order. Mirrors the standalone
 * scripts/scenarios/cancel-order/2-cancel-order.sh path (which calls
 * `temporal workflow update execute --name cancelOrder` directly), giving
 * the web UI an equivalent HTTP entry point.
 *
 * URI Template: /api/v1/commerce-app/orders/{orderId}/cancel
 */
@RestController
@RequestMapping("/api/v1/commerce-app")
@Tag(name = "Commerce Webhooks", description = "Webhook endpoints for commerce app integration")
public class CancelOrderController {

    private static final Logger logger = LoggerFactory.getLogger(CancelOrderController.class);

    private final WorkflowClient workflowClient;

    public CancelOrderController(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    public record CancelOrderApiRequest(String reason, String cancelledBy) {
    }

    @PostMapping("/orders/{orderId}/cancel")
    @Operation(
            summary = "Cancel an order",
            description = "Sends the cancelOrder Update to the order's apps.Order workflow"
    )
    public ResponseEntity<Void> cancelOrder(
            @PathVariable String orderId,
            @RequestBody CancelOrderApiRequest request) {

        logger.info("Cancelling order {}", orderId);

        Instant now = Instant.now();
        var cancelRequest = CancelOrderRequest.newBuilder()
                .setReason(request.reason())
                .setCancelledBy(request.cancelledBy())
                .setTimestamp(Timestamp.newBuilder()
                        .setSeconds(now.getEpochSecond())
                        .setNanos(now.getNano())
                        .build())
                .build();

        Order workflow = workflowClient.newWorkflowStub(Order.class, orderId);
        try {
            workflow.cancelOrder(cancelRequest);
            return ResponseEntity.accepted().build();
        } catch (WorkflowNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (WorkflowUpdateException e) {
            logger.error("Failed to cancel order {}: {}", orderId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
