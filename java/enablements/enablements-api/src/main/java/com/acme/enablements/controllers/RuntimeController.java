package com.acme.enablements.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Reports where enablements-api is running, so the web Admin page can disable
 * worker-version promotion outside Kubernetes. Promotion creates and deletes
 * Kubernetes Deployments, so it only works on KinD or k3d.
 *
 * URI Template: /api/v1/enablements/runtime
 */
@RestController
@RequestMapping("/api/v1/enablements")
public class RuntimeController {

    static final String KUBERNETES_SERVICE_HOST = "KUBERNETES_SERVICE_HOST";

    public record RuntimeInfo(boolean kubernetes) {
    }

    @GetMapping("/runtime")
    public ResponseEntity<RuntimeInfo> runtime() {
        return ResponseEntity.ok(detect(System.getenv()));
    }

    /** Returns kubernetes=true when the kubelet-injected KUBERNETES_SERVICE_HOST variable is set. */
    static RuntimeInfo detect(Map<String, String> env) {
        String host = env.get(KUBERNETES_SERVICE_HOST);
        return new RuntimeInfo(host != null && !host.isBlank());
    }
}
