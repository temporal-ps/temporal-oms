package com.acme.enablements.controllers;

import com.acme.enablements.deployment.OmsVersionCatalog;
import com.acme.enablements.deployment.OmsVersionRolloutService;
import com.acme.proto.acme.enablements.v1.ListOmsVersionsResponse;
import com.acme.proto.acme.enablements.v1.OmsVersionRolloutState;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an operator pick a single OMS version and promote apps, processing,
 * and fulfillment to the matching component versions in one action
 * (hosting.md "OMS-Version-Driven Promotion"), instead of the three
 * independent per-context promotions {@link DeploymentsController} exposes.
 *
 * URI Template: /api/v1/enablements/oms-versions, /api/v1/enablements/oms-rollouts
 */
@RestController
@RequestMapping("/api/v1/enablements")
public class OmsVersionsController {

    private final OmsVersionRolloutService rolloutService;

    public OmsVersionsController(OmsVersionRolloutService rolloutService) {
        this.rolloutService = rolloutService;
    }

    public record StartRolloutRequest(String omsVersion) {
    }

    @GetMapping("/oms-versions")
    public ResponseEntity<ListOmsVersionsResponse> listOmsVersions() {
        return ResponseEntity.ok(ListOmsVersionsResponse.newBuilder().addAllRows(OmsVersionCatalog.rows()).build());
    }

    @PostMapping("/oms-rollouts")
    public ResponseEntity<OmsVersionRolloutState> startRollout(@RequestBody StartRolloutRequest request) {
        return ResponseEntity.ok(rolloutService.start(request.omsVersion()));
    }

    @GetMapping("/oms-rollouts/{rolloutId}")
    public ResponseEntity<OmsVersionRolloutState> getRollout(@PathVariable String rolloutId) {
        try {
            return ResponseEntity.ok(rolloutService.getState(rolloutId));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return ResponseEntity.notFound().build();
            }
            throw e;
        }
    }
}
