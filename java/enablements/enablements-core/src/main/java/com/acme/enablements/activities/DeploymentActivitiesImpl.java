package com.acme.enablements.activities;

import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Promotes a bounded context (apps, processing, fulfillment) to a new component version
 * on demand (hosting.md Mode B), independent of load generation.
 * <p>
 * apps and fulfillment run as plain Kubernetes Deployments: promotion applies a new
 * versioned Deployment/Service from the generalized template, calls
 * {@code set-current-version}, confirms via {@code describe}, then removes prior-version
 * Deployments for that bounded context. processing keeps its existing
 * {@code k8s/processing-versioned} WorkerDeployment CRD: promotion patches that
 * resource's image and env in place instead of applying a new Deployment.
 */
@Component("deployment-activities")
public class DeploymentActivitiesImpl implements DeploymentActivities {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentActivitiesImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private record BoundedContextConfig(
            String temporalNamespace,
            String k8sNamespace,
            String workflowClassPackage,
            String workflowClassEnvVar,
            String imageRepository,
            int managementPort,
            String configMapName,
            String secretName,
            boolean managedByWorkerDeploymentCrd) {
    }

    private static final Map<String, BoundedContextConfig> BOUNDED_CONTEXTS = Map.of(
            "apps", new BoundedContextConfig(
                    "apps", "temporal-oms-apps", "com.acme.apps.workflows",
                    "ACME_APPS_ORDER_WORKFLOW_CLASS", "temporal-oms/apps-worker", 9092,
                    "temporal-apps-config", "temporal-apps-api-key", false),
            "processing", new BoundedContextConfig(
                    "processing", "temporal-oms-processing", "com.acme.processing.workflows",
                    "ACME_PROCESSING_ORDER_WORKFLOW_CLASS", "temporal-oms/processing-workers", 9092,
                    "temporal-processing-config", "temporal-processing-api-key", true),
            "fulfillment", new BoundedContextConfig(
                    "fulfillment", "temporal-oms-fulfillment", "com.acme.fulfillment.workflows",
                    "ACME_FULFILLMENT_ORDER_WORKFLOW_CLASS", "temporal-oms/fulfillment-workers", 9072,
                    "temporal-fulfillment-config", "temporal-fulfillment-api-key", false));

    @Value("${enablements.deployment.manifest-template:k8s/base/templates/worker-deployment-template.yaml}")
    private String manifestTemplatePath;

    @Value("${enablements.deployment.set-current-version-timeout-seconds:60}")
    private int setCurrentVersionTimeoutSeconds;

    @Value("${enablements.deployment.retry-interval-seconds:2}")
    private int retryIntervalSeconds;

    @Override
    public DeployWorkerVersionResponse deployWorkerVersion(DeployWorkerVersionRequest cmd) {
        logger.info("Deploying {} version={}, buildId={}", cmd.getDeploymentName(), cmd.getVersion(), cmd.getBuildId());

        BoundedContextConfig context = BOUNDED_CONTEXTS.get(cmd.getDeploymentName());
        if (context == null) {
            throw new IllegalArgumentException("Unknown bounded context: " + cmd.getDeploymentName()
                    + " (expected one of " + BOUNDED_CONTEXTS.keySet() + ")");
        }
        String workflowClass = context.workflowClassPackage() + "." + cmd.getVersion() + ".OrderImpl";
        int replicas = cmd.hasReplicaCount() ? cmd.getReplicaCount() : 1;

        try {
            if (context.managedByWorkerDeploymentCrd()) {
                patchWorkerDeploymentCrd(cmd, context, workflowClass);
            } else {
                applyVersionedDeployment(cmd, context, workflowClass, replicas);
            }

            boolean currentVersionSet = setCurrentVersion(cmd.getDeploymentName(), cmd.getBuildId(), context.temporalNamespace());
            String describeOutput = describeDeployment(cmd.getDeploymentName(), context.temporalNamespace());

            if (currentVersionSet && !context.managedByWorkerDeploymentCrd()) {
                removeStaleVersions(cmd, context);
            }

            logger.info("{} promoted to buildId={} (currentVersionSet={})",
                    cmd.getDeploymentName(), cmd.getBuildId(), currentVersionSet);

            return DeployWorkerVersionResponse.newBuilder()
                    .setWorkflowClass(workflowClass)
                    .setCurrentVersionSet(currentVersionSet)
                    .setDescribeOutput(describeOutput)
                    .build();
        } catch (Exception e) {
            logger.error("Failed to deploy {} buildId {}", cmd.getDeploymentName(), cmd.getBuildId(), e);
            throw new RuntimeException("Worker deployment failed for " + cmd.getDeploymentName()
                    + " buildId " + cmd.getBuildId(), e);
        }
    }

    @Override
    public String currentBuildId(String deploymentName) {
        BoundedContextConfig context = BOUNDED_CONTEXTS.get(deploymentName);
        if (context == null) {
            throw new IllegalArgumentException("Unknown bounded context: " + deploymentName
                    + " (expected one of " + BOUNDED_CONTEXTS.keySet() + ")");
        }
        return parseCurrentBuildId(describeDeployment(deploymentName, context.temporalNamespace()));
    }

    /**
     * Extracts the current build id from {@code temporal worker deployment describe
     * --output json}'s response, shaped as {@code DescribeWorkerDeploymentResponse}
     * (io.temporal.api.workflowservice.v1, matching this repo's pinned
     * temporal-serviceclient version): {@code workerDeploymentInfo.routingConfig
     * .currentDeploymentVersion.buildId}. Returns an empty string if the deployment
     * has no current version yet, or if the field can't be parsed.
     */
    private static String parseCurrentBuildId(String describeOutputJson) {
        if (describeOutputJson == null || describeOutputJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(describeOutputJson);
            return root.path("workerDeploymentInfo").path("routingConfig")
                    .path("currentDeploymentVersion").path("buildId").asText("");
        } catch (Exception e) {
            logger.warn("Failed to parse current build id from describe output (non-fatal): {}", e.getMessage());
            return "";
        }
    }

    /**
     * Apply a new versioned Deployment/Service for a plain-Deployment bounded context
     * (apps, fulfillment) from the generalized template.
     */
    private void applyVersionedDeployment(DeployWorkerVersionRequest cmd, BoundedContextConfig context,
                                           String workflowClass, int replicas) throws IOException, InterruptedException {
        String resourceName = cmd.getDeploymentName() + "-worker-" + cmd.getBuildId();
        String image = context.imageRepository() + ":" + cmd.getBuildId();

        String manifest = Files.readString(Paths.get(manifestTemplatePath))
                .replace("{NAME}", resourceName)
                .replace("{NAMESPACE}", context.k8sNamespace())
                .replace("{REPLICAS}", String.valueOf(replicas))
                .replace("{IMAGE}", image)
                .replace("{MANAGEMENT_PORT}", String.valueOf(context.managementPort()))
                .replace("{DEPLOYMENT_NAME}", cmd.getDeploymentName())
                .replace("{BUILD_ID}", cmd.getBuildId())
                .replace("{WORKFLOW_CLASS_ENV}", context.workflowClassEnvVar())
                .replace("{WORKFLOW_CLASS_VALUE}", workflowClass)
                .replace("{CONFIGMAP_NAME}", context.configMapName())
                .replace("{SECRET_NAME}", context.secretName());

        applyManifestViaKubectl(manifest);
    }

    /**
     * Patch the existing WorkerDeployment CRD (processing) in place: new image tag and
     * the env vars that select this version's workflow class and build id.
     */
    private void patchWorkerDeploymentCrd(DeployWorkerVersionRequest cmd, BoundedContextConfig context,
                                           String workflowClass) throws IOException, InterruptedException {
        String resourceName = cmd.getDeploymentName() + "-workers";
        String image = context.imageRepository() + ":" + cmd.getBuildId();

        executeCommand(new String[]{
                "kubectl", "patch", "workerdeployment", resourceName,
                "-n", context.k8sNamespace(),
                "--type=json",
                "-p", "[{\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/image\",\"value\":\"" + image + "\"}]"
        });

        executeCommand(new String[]{
                "kubectl", "set", "env", "workerdeployment/" + resourceName,
                "-n", context.k8sNamespace(),
                "TEMPORAL_WORKER_BUILD_ID=" + cmd.getBuildId(),
                context.workflowClassEnvVar() + "=" + workflowClass
        });
    }

    /**
     * Remove Deployments left over from a prior promotion of this bounded context, now
     * that {@code newBuildId} is current. Best-effort: a failure here does not fail the
     * promotion itself, since the cutover already succeeded.
     */
    private void removeStaleVersions(DeployWorkerVersionRequest cmd, BoundedContextConfig context) {
        try {
            executeCommand(new String[]{
                    "kubectl", "delete", "deployment",
                    "-n", context.k8sNamespace(),
                    "-l", "bounded-context=" + cmd.getDeploymentName() + ",oms-build-id!=" + cmd.getBuildId(),
                    "--ignore-not-found"
            });
        } catch (Exception e) {
            logger.warn("Failed to remove stale {} Deployments (non-fatal)", cmd.getDeploymentName(), e);
        }
    }

    /**
     * Call {@code temporal worker deployment set-current-version}, retrying until it
     * succeeds or {@code setCurrentVersionTimeoutSeconds} elapses (matches
     * workshop/safe-fulfillment-handoff/scripts/_lib.sh's set_current_version retry loop).
     */
    private boolean setCurrentVersion(String deploymentName, String buildId, String namespace)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + setCurrentVersionTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                executeCommand(new String[]{
                        "temporal", "worker", "deployment", "set-current-version",
                        "--deployment-name", deploymentName,
                        "--build-id", buildId,
                        "--namespace", namespace,
                        "--yes"
                });
                return true;
            } catch (Exception e) {
                logger.debug("set-current-version not ready yet for {}/{}: {}", deploymentName, buildId, e.getMessage());
                Thread.sleep(retryIntervalSeconds * 1000L);
            }
        }
        logger.error("Timed out setting {} current version to {} in namespace {}", deploymentName, buildId, namespace);
        return false;
    }

    private String describeDeployment(String deploymentName, String namespace) {
        try {
            return executeCommandCapture(new String[]{
                    "temporal", "worker", "deployment", "describe",
                    "--name", deploymentName,
                    "--namespace", namespace,
                    "--output", "json"
            });
        } catch (Exception e) {
            logger.warn("Failed to describe {} deployment (non-fatal)", deploymentName, e);
            return "";
        }
    }

    private void applyManifestViaKubectl(String manifest) throws IOException, InterruptedException {
        java.nio.file.Path tempFile = Files.createTempFile("k8s-manifest-", ".yaml");
        try {
            Files.writeString(tempFile, manifest);
            executeCommand(new String[]{"kubectl", "apply", "-f", tempFile.toString()});
        } finally {
            Files.delete(tempFile);
        }
    }

    private void executeCommand(String[] cmd) throws IOException, InterruptedException {
        executeCommandCapture(cmd);
    }

    private String executeCommandCapture(String[] cmd) throws IOException, InterruptedException {
        logger.debug("Executing command: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                logger.debug("command output: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Command failed with exit code " + exitCode + ": " + output);
        }
        return output.toString();
    }
}
