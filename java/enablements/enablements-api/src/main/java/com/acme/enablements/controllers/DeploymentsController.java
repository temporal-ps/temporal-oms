package com.acme.enablements.controllers;

import com.acme.enablements.deployment.DeploymentService;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an operator promote a bounded context (apps, processing, fulfillment) to a
 * new component version from the Admin UI, independent of load generation
 * (hosting.md Mode B). Calls {@link DeploymentService}, which invokes
 * {@code DeploymentActivitiesImpl.deployWorkerVersion} as a standalone activity, the
 * same pattern {@link EnablementsController} uses for load generation.
 *
 * URI Template: /api/v1/enablements/deployments/{boundedContext}
 */
@RestController
@RequestMapping("/api/v1/enablements/deployments")
public class DeploymentsController {

    private final DeploymentService deploymentService;

    public DeploymentsController(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    public record PromoteRequest(String version, String buildId, Integer replicaCount) {
    }

    @PostMapping("/{boundedContext}")
    public ResponseEntity<DeployWorkerVersionResponse> promote(
            @PathVariable String boundedContext, @RequestBody PromoteRequest request) {
        DeployWorkerVersionRequest.Builder cmd = DeployWorkerVersionRequest.newBuilder()
                .setDeploymentName(boundedContext)
                .setVersion(request.version())
                .setBuildId(request.buildId() != null && !request.buildId().isBlank()
                        ? request.buildId() : request.version());
        if (request.replicaCount() != null) {
            cmd.setReplicaCount(request.replicaCount());
        }
        return ResponseEntity.ok(deploymentService.deploy(cmd.build()));
    }
}
