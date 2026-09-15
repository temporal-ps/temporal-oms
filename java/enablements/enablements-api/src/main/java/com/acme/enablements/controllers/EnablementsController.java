package com.acme.enablements.controllers;

import com.acme.enablements.loadgen.LoadGeneratorService;
import com.acme.proto.acme.enablements.v1.StartWorkerVersionEnablementRequest;
import com.acme.proto.acme.enablements.v1.WorkerVersionEnablementState;
import io.temporal.client.WorkflowException;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets the web UI drive the WorkerVersionEnablement load generator
 * (start/pause/resume/stop, and poll its state) instead of the Temporal CLI.
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
    public ResponseEntity<WorkerVersionEnablementState> start(@RequestBody StartWorkerVersionEnablementRequest request) {
        try {
            return ResponseEntity.ok(loadGenerator.start(request));
        } catch (WorkflowExecutionAlreadyStarted e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/{enablementId}/pause")
    public ResponseEntity<Void> pause(@PathVariable String enablementId) {
        try {
            loadGenerator.pause(enablementId);
            return ResponseEntity.accepted().build();
        } catch (WorkflowException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{enablementId}/resume")
    public ResponseEntity<Void> resume(@PathVariable String enablementId) {
        try {
            loadGenerator.resume(enablementId);
            return ResponseEntity.accepted().build();
        } catch (WorkflowException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{enablementId}/stop")
    public ResponseEntity<Void> stop(@PathVariable String enablementId, @RequestBody(required = false) StopRequest request) {
        String reason = request != null && request.reason() != null ? request.reason() : "Stopped from UI";
        try {
            loadGenerator.stop(enablementId, reason);
            return ResponseEntity.accepted().build();
        } catch (WorkflowException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{enablementId}")
    public ResponseEntity<WorkerVersionEnablementState> getState(@PathVariable String enablementId) {
        try {
            return ResponseEntity.ok(loadGenerator.getState(enablementId));
        } catch (WorkflowException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
