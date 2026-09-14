package com.acme.enablements.activities;

import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Implementation of DeploymentActivities for managing Temporal worker versions.
 *
 * Handles Kubernetes deployments of v2 workers and build-id compatibility registration.
 */
@Component("deployment-activities")
public class DeploymentActivitiesImpl implements DeploymentActivities {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentActivitiesImpl.class);

    @Value("${enablements.deployment.manifest-template:k8s/base/processing/processing-workers-deployment-template.yaml}")
    private String manifestTemplatePath;

    /**
     * Deploy v2 workers to Kubernetes by applying WorkerDeployment CRD.
     * <p>
     * Reads the manifest template, substitutes version/build-id variables, and applies via kubectl.
     *
     * @return
     */
    @Override
    public DeployWorkerVersionResponse deployWorkerVersion(DeployWorkerVersionRequest cmd) {
        logger.info("Deploying workers: version={}, buildId={}", cmd.getVersion(), cmd.getBuildId());

        try {
            int replicas = cmd.hasReplicaCount() ? cmd.getReplicaCount() : 1;
            String manifest = readAndSubstituteTemplate(cmd.getVersion(), cmd.getBuildId(), replicas);
            applyManifestViaKubectl(manifest);
            logger.info("{} workers deployed successfully", cmd.getBuildId());

        } catch (Exception e) {
            logger.error("Failed to deploy workers for buildId {}", cmd.getBuildId(), e);
            throw new RuntimeException("Worker deployment failed for buildId " + cmd.getBuildId(), e);
        }
        return DeployWorkerVersionResponse.getDefaultInstance();
    }

    /**
     * Register v2 build-id as compatible with v1 in Temporal.
     *
     * Marks v1 workers as compatible with v2, so new workflows route to v2.
     */
    @Override
    public void registerCompatibility() {
        logger.info("Registering v2 build-id compatibility with Temporal");

        try {
            String[] cmd = {
                    "temporal", "worker-build-id", "update-compatibility",
                    "--build-id", "processing-worker:v1",
                    "--compatible-with", "processing-worker:v2",
                    "--namespace", "processing"
            };

            executeCommand(cmd);
            logger.info("Build-id compatibility registered: v1 compatible with v2");

        } catch (Exception e) {
            logger.error("Failed to register build-id compatibility", e);
            throw new RuntimeException("Build-id registration failed", e);
        }
    }

    /**
     * Read manifest template and substitute version variables.
     *
     * @param version version identifier (e.g., "v2", "v3")
     * @param buildId build-id value (e.g., "processing-worker:v2")
     * @param replicas number of replicas to deploy
     * @return manifest with substitutions applied
     * @throws IOException if template file cannot be read
     */
    private String readAndSubstituteTemplate(String version, String buildId, int replicas)
            throws IOException {
        String manifest = Files.readString(Paths.get(manifestTemplatePath));

        manifest = manifest.replace("{VERSION}", version)
                .replace("{BUILD_ID}", buildId)
                .replace("{REPLICAS}", String.valueOf(replicas));

        logger.debug("Substituted manifest for version: {}, buildId: {}, replicas: {}",
                version, buildId, replicas);

        return manifest;
    }

    /**
     * Apply a Kubernetes manifest using kubectl.
     *
     * @param manifest YAML manifest content
     * @throws IOException if kubectl execution fails
     * @throws InterruptedException if process is interrupted
     */
    private void applyManifestViaKubectl(String manifest) throws IOException, InterruptedException {
        // Write manifest to temp file to avoid shell escaping issues
        java.nio.file.Path tempFile = Files.createTempFile("k8s-manifest-", ".yaml");
        try {
            Files.writeString(tempFile, manifest);

            String[] cmd = {"kubectl", "apply", "-f", tempFile.toString()};
            executeCommand(cmd);
        } finally {
            Files.delete(tempFile);
        }
    }

    /**
     * Execute a system command and wait for completion.
     *
     * @param cmd command array
     * @throws IOException if execution fails
     * @throws InterruptedException if process is interrupted
     */
    private void executeCommand(String[] cmd) throws IOException, InterruptedException {
        logger.debug("Executing command: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Capture output for logging
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("kubectl output: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code: " + exitCode);
        }
    }
}
