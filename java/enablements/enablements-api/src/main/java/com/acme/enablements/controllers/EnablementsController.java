package com.acme.enablements.controllers;

import com.acme.enablements.loadgen.LoadGeneratorService;
import com.acme.proto.acme.enablements.v1.LoadGenerationState;
import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.client.ActivityAlreadyStartedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the web UI drive the load generator (start/stop, and poll its state)
 * instead of the Temporal CLI. The load generator runs as a Standalone
 * Activity started directly by {@link LoadGeneratorService}, not through a
 * workflow, so there is no pause/resume: Temporal exposes Activity
 * Pause/Unpause only as CLI/gRPC operator commands, not as Client SDK
 * methods callable from here.
 *
 * Does not expose deployWorkerVersion; worker-version transition control is
 * a separate concern.
 *
 * URI Template: /api/v1/enablements/worker-version
 */
@RestController
@RequestMapping("/api/v1/enablements/worker-version")
public class EnablementsController {

    private final LoadGeneratorService loadGenerator;

    public EnablementsController(LoadGeneratorService loadGenerator) {
        this.loadGenerator = loadGenerator;
    }

    public record StopRequest(String reason) {
    }

    @PostMapping("/start")
    public ResponseEntity<LoadGenerationState> start(@RequestBody StartWorkerVersionEnablementRequest request) {
        try {
            return ResponseEntity.ok(loadGenerator.start(request));
        } catch (ActivityAlreadyStartedException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/{enablementId}/stop")
    public ResponseEntity<Void> stop(@PathVariable String enablementId, @RequestBody(required = false) StopRequest request) {
        String reason = request != null && request.reason() != null ? request.reason() : "Stopped from UI";
        try {
            loadGenerator.stop(enablementId, reason);
            return ResponseEntity.accepted().build();
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return ResponseEntity.notFound().build();
            }
            throw e;
        }
    }

    @GetMapping("/{enablementId}")
    public ResponseEntity<LoadGenerationState> getState(@PathVariable String enablementId) {
        try {
            return ResponseEntity.ok(loadGenerator.getState(enablementId));
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return ResponseEntity.notFound().build();
            }
            throw e;
        }
    }
}
