package com.acme.enablements.activities;

import com.acme.proto.acme.enablements.v1.DeployWorkerVersionRequest;
import com.acme.proto.acme.enablements.v1.DeployWorkerVersionResponse;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.protobuf.TextFormat;
import io.grpc.StatusRuntimeException;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.Yaml;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentResponse;
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;

/**
 * Promotes a bounded context (apps, processing, fulfillment) to a new component version
 * on demand (hosting.md Mode B), independent of load generation.
 * <p>
 * apps and fulfillment run as plain Kubernetes Deployments: promotion applies a new
 * versioned Deployment/Service from the generalized template, calls
 * {@code set-current-version}, confirms via {@code describe}, then removes prior-version
 * Deployments/Services for that bounded context. processing keeps its existing
 * {@code k8s/processing-versioned} WorkerDeployment CRD: promotion patches that
 * resource's image and env in place instead of applying a new Deployment.
 * <p>
 * Talks to Kubernetes via {@code io.kubernetes:client-java} and to Temporal via the SDK's
 * native {@code setWorkerDeploymentCurrentVersion}/{@code describeWorkerDeployment} gRPC
 * calls, not the {@code kubectl}/{@code temporal} CLI binaries (hosting.md "Deploy and
 * Version Clients") - this class is loaded on every enablements-workers boot, including
 * Level 1's plain local run with no Kubernetes at all, so the Kubernetes client is built
 * lazily on first use, not in the constructor.
 */
@Component("deployment-activities")
public class DeploymentActivitiesImpl implements DeploymentActivities {

    private static final Logger logger = LoggerFactory.getLogger(DeploymentActivitiesImpl.class);
    private static final Gson GSON = new Gson();
    private static final String WORKER_DEPLOYMENT_GROUP = "temporal.io";
    private static final String WORKER_DEPLOYMENT_VERSION = "v1alpha1";
    private static final String WORKER_DEPLOYMENT_PLURAL = "workerdeployments";
    private static final String BUILD_ID_ENV_VAR = "TEMPORAL_WORKER_BUILD_ID";
    // Every version's code ships in one image (spec.md's package-per-version convention);
    // the workflow-class env var (below), not the image tag, is what selects which
    // OrderImpl class actually runs - there is no per-version image tag to request. Kept
    // as its own env var rather than derived from TEMPORAL_WORKER_BUILD_ID: the Safe
    // Fulfillment Handoff workshop deliberately edits an existing vN package in place and
    // deploys it under an unrelated, decoupled build-id, so the two must stay independent.
    private static final String RUNTIME_IMAGE_TAG = "latest";

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

    @Value("${enablements.deployment.crd-rollout-timeout-seconds:180}")
    private int crdRolloutTimeoutSeconds;

    private final WorkflowServiceStubs workflowServiceStubs;
    private volatile ApiClient k8sApiClient;

    public DeploymentActivitiesImpl(WorkflowClient workflowClient) {
        this.workflowServiceStubs = workflowClient.getWorkflowServiceStubs();
    }

    @Override
    public DeployWorkerVersionResponse deployWorkerVersion(DeployWorkerVersionRequest cmd) {
        logger.info("Deploying {} version={}, buildId={}", cmd.getDeploymentName(), cmd.getVersion(), cmd.getBuildId());

        BoundedContextConfig context = requireContext(cmd.getDeploymentName());
        String workflowClass = context.workflowClassPackage() + "." + cmd.getVersion() + ".OrderImpl";
        int replicas = cmd.hasReplicaCount() ? cmd.getReplicaCount() : 1;

        try {
            boolean currentVersionSet;
            if (context.managedByWorkerDeploymentCrd()) {
                // The Temporal Worker Controller owns this transition: it computes its own
                // build-id from image tag + pod spec hash (confirmed live: patching this CRD
                // with TEMPORAL_WORKER_BUILD_ID=v1 registered as current build-id "latest-f966",
                // not "v1") and runs its own Progressive rollout (ramp/pause steps already in
                // the CRD). Calling setWorkerDeploymentCurrentVersion directly here would target
                // a build-id string that was never actually registered and can never confirm.
                // Patch the pod template, then wait for the controller's own rollout to finish.
                patchWorkerDeploymentCrd(cmd, context, workflowClass);
                currentVersionSet = waitForWorkerDeploymentCrdRollout(cmd.getDeploymentName(), context.k8sNamespace());
            } else {
                applyVersionedDeployment(cmd, context, workflowClass, replicas);
                currentVersionSet = setCurrentVersion(cmd.getDeploymentName(), cmd.getBuildId(), context.temporalNamespace());
                if (currentVersionSet) {
                    removeStaleVersions(cmd, context);
                }
            }

            String describeOutput = describeDeployment(cmd.getDeploymentName(), context.temporalNamespace());

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
        BoundedContextConfig context = requireContext(deploymentName);
        return tryDescribeWorkerDeployment(context.temporalNamespace(), deploymentName)
                .map(r -> r.getWorkerDeploymentInfo().getRoutingConfig().getCurrentDeploymentVersion().getBuildId())
                .orElse("");
    }

    private static BoundedContextConfig requireContext(String deploymentName) {
        BoundedContextConfig context = BOUNDED_CONTEXTS.get(deploymentName);
        if (context == null) {
            throw new IllegalArgumentException("Unknown bounded context: " + deploymentName
                    + " (expected one of " + BOUNDED_CONTEXTS.keySet() + ")");
        }
        return context;
    }

    /**
     * Apply a new versioned Deployment/Service for a plain-Deployment bounded context
     * (apps, fulfillment) from the generalized template.
     */
    private void applyVersionedDeployment(DeployWorkerVersionRequest cmd, BoundedContextConfig context,
                                           String workflowClass, int replicas) throws IOException, ApiException {
        String resourceName = cmd.getDeploymentName() + "-worker-" + cmd.getBuildId();
        String image = context.imageRepository() + ":" + RUNTIME_IMAGE_TAG;

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

        for (Object resource : Yaml.loadAll(manifest)) {
            if (resource instanceof V1Deployment deployment) {
                applyDeployment(context.k8sNamespace(), deployment);
            } else if (resource instanceof V1Service service) {
                applyService(context.k8sNamespace(), service);
            }
        }
    }

    /** Create the Deployment, or replace it in place if a prior attempt already created it. */
    private void applyDeployment(String namespace, V1Deployment deployment) throws ApiException, IOException {
        String name = deployment.getMetadata().getName();
        try {
            appsV1Api().createNamespacedDeployment(namespace, deployment).fieldManager("enablements-workers").execute();
        } catch (ApiException e) {
            if (e.getCode() != 409) {
                throw e;
            }
            V1Deployment existing = appsV1Api().readNamespacedDeployment(name, namespace).execute();
            deployment.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
            appsV1Api().replaceNamespacedDeployment(name, namespace, deployment).fieldManager("enablements-workers").execute();
        }
    }

    /** Create the Service, or replace it in place if a prior attempt already created it. */
    private void applyService(String namespace, V1Service service) throws ApiException, IOException {
        String name = service.getMetadata().getName();
        try {
            coreV1Api().createNamespacedService(namespace, service).fieldManager("enablements-workers").execute();
        } catch (ApiException e) {
            if (e.getCode() != 409) {
                throw e;
            }
            V1Service existing = coreV1Api().readNamespacedService(name, namespace).execute();
            service.getMetadata().setResourceVersion(existing.getMetadata().getResourceVersion());
            coreV1Api().replaceNamespacedService(name, namespace, service).fieldManager("enablements-workers").execute();
        }
    }

    /**
     * Patch the existing WorkerDeployment CRD (processing) in place: new image tag and
     * the env vars that select this version's workflow class and build id. A generic CRD
     * has no typed model, so this reads it as a {@link JsonObject}, mutates the same
     * fields {@code kubectl patch}/{@code kubectl set env} used to, and replaces it
     * whole - {@code kubectl set env}'s upsert-by-name behavior for the env list (add if
     * missing, update in place if present, leave every other entry alone) since the CRD's
     * base manifest doesn't predeclare these two env vars.
     */
    private void patchWorkerDeploymentCrd(DeployWorkerVersionRequest cmd, BoundedContextConfig context,
                                           String workflowClass) throws ApiException, IOException {
        String resourceName = cmd.getDeploymentName() + "-workers";
        String image = context.imageRepository() + ":" + RUNTIME_IMAGE_TAG;

        Object raw = customObjectsApi().getNamespacedCustomObject(
                WORKER_DEPLOYMENT_GROUP, WORKER_DEPLOYMENT_VERSION, context.k8sNamespace(),
                WORKER_DEPLOYMENT_PLURAL, resourceName).execute();
        JsonObject workerDeployment = GSON.toJsonTree(raw).getAsJsonObject();

        JsonObject container = workerDeployment
                .getAsJsonObject("spec")
                .getAsJsonObject("template")
                .getAsJsonObject("spec")
                .getAsJsonArray("containers")
                .get(0).getAsJsonObject();
        container.addProperty("image", image);
        upsertEnvVar(container, BUILD_ID_ENV_VAR, cmd.getBuildId());
        upsertEnvVar(container, context.workflowClassEnvVar(), workflowClass);

        customObjectsApi().replaceNamespacedCustomObject(
                WORKER_DEPLOYMENT_GROUP, WORKER_DEPLOYMENT_VERSION, context.k8sNamespace(),
                WORKER_DEPLOYMENT_PLURAL, resourceName, workerDeployment).fieldManager("enablements-workers").execute();
    }

    /**
     * Poll the WorkerDeployment CRD's own status until its Progressive rollout finishes
     * (the controller's ramp/pause steps, not anything this activity drives) or
     * {@code crdRolloutTimeoutSeconds} elapses. Rollout is complete once
     * {@code status.currentVersion.buildID} matches {@code status.targetVersion.buildID}
     * - both are the controller's own computed build-id, unrelated to the
     * {@code TEMPORAL_WORKER_BUILD_ID} value patched into the pod template.
     */
    private boolean waitForWorkerDeploymentCrdRollout(String deploymentName, String namespace)
            throws ApiException, IOException, InterruptedException {
        String resourceName = deploymentName + "-workers";
        long deadline = System.currentTimeMillis() + crdRolloutTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            Object raw = customObjectsApi().getNamespacedCustomObject(
                    WORKER_DEPLOYMENT_GROUP, WORKER_DEPLOYMENT_VERSION, namespace,
                    WORKER_DEPLOYMENT_PLURAL, resourceName).execute();
            JsonObject status = GSON.toJsonTree(raw).getAsJsonObject().getAsJsonObject("status");
            String currentBuildId = crdVersionBuildId(status, "currentVersion");
            String targetBuildId = crdVersionBuildId(status, "targetVersion");
            if (currentBuildId != null && currentBuildId.equals(targetBuildId)) {
                return true;
            }
            logger.debug("{} WorkerDeployment CRD rollout not complete yet (current={}, target={})",
                    deploymentName, currentBuildId, targetBuildId);
            Thread.sleep(retryIntervalSeconds * 1000L);
        }
        logger.error("Timed out waiting for {} WorkerDeployment CRD rollout in namespace {}", deploymentName, namespace);
        return false;
    }

    private static String crdVersionBuildId(JsonObject status, String versionField) {
        if (status == null || !status.has(versionField) || status.get(versionField).isJsonNull()) {
            return null;
        }
        JsonObject version = status.getAsJsonObject(versionField);
        return version.has("buildID") ? version.get("buildID").getAsString() : null;
    }

    private static void upsertEnvVar(JsonObject container, String name, String value) {
        JsonArray env = container.has("env") ? container.getAsJsonArray("env") : new JsonArray();
        for (JsonElement e : env) {
            JsonObject entry = e.getAsJsonObject();
            if (name.equals(entry.get("name").getAsString())) {
                entry.addProperty("value", value);
                return;
            }
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("name", name);
        entry.addProperty("value", value);
        env.add(entry);
        container.add("env", env);
    }

    /**
     * Remove Deployments/Services left over from a prior promotion of this bounded
     * context, now that {@code newBuildId} is current. Best-effort: a failure here does
     * not fail the promotion itself, since the cutover already succeeded. A delete-by-
     * selector matching zero resources is a normal success, not an error - the k8s API's
     * DeleteCollection returns an empty list, unlike deleting one resource by name.
     */
    private void removeStaleVersions(DeployWorkerVersionRequest cmd, BoundedContextConfig context) {
        String selector = "bounded-context=" + cmd.getDeploymentName() + ",oms-build-id!=" + cmd.getBuildId();
        try {
            appsV1Api().deleteCollectionNamespacedDeployment(context.k8sNamespace())
                    .labelSelector(selector).execute();
            coreV1Api().deleteCollectionNamespacedService(context.k8sNamespace())
                    .labelSelector(selector).execute();
        } catch (Exception e) {
            logger.warn("Failed to remove stale {} resources (non-fatal)", cmd.getDeploymentName(), e);
        }
    }

    /**
     * Call the SDK's native {@code setWorkerDeploymentCurrentVersion} RPC, retrying until
     * it succeeds or {@code setCurrentVersionTimeoutSeconds} elapses (matches
     * workshop/safe-fulfillment-handoff/scripts/_lib.sh's set_current_version retry loop -
     * a fresh Deployment's pollers may not have registered yet).
     */
    private boolean setCurrentVersion(String deploymentName, String buildId, String namespace)
            throws InterruptedException {
        var request = SetWorkerDeploymentCurrentVersionRequest.newBuilder()
                .setNamespace(namespace)
                .setDeploymentName(deploymentName)
                .setBuildId(buildId)
                .build();
        long deadline = System.currentTimeMillis() + setCurrentVersionTimeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                workflowServiceStubs.blockingStub().setWorkerDeploymentCurrentVersion(request);
                return true;
            } catch (StatusRuntimeException e) {
                logger.debug("set-current-version not ready yet for {}/{}: {}", deploymentName, buildId, e.getMessage());
                Thread.sleep(retryIntervalSeconds * 1000L);
            }
        }
        logger.error("Timed out setting {} current version to {} in namespace {}", deploymentName, buildId, namespace);
        return false;
    }

    private String describeDeployment(String deploymentName, String namespace) {
        return tryDescribeWorkerDeployment(namespace, deploymentName)
                .map(TextFormat.printer()::printToString)
                .orElse("");
    }

    private Optional<DescribeWorkerDeploymentResponse> tryDescribeWorkerDeployment(String namespace, String deploymentName) {
        try {
            return Optional.of(workflowServiceStubs.blockingStub().describeWorkerDeployment(
                    DescribeWorkerDeploymentRequest.newBuilder()
                            .setNamespace(namespace)
                            .setDeploymentName(deploymentName)
                            .build()));
        } catch (StatusRuntimeException e) {
            logger.warn("Failed to describe {} deployment (non-fatal): {}", deploymentName, e.getMessage());
            return Optional.empty();
        }
    }

    private AppsV1Api appsV1Api() throws IOException {
        return new AppsV1Api(k8sApiClient());
    }

    private CoreV1Api coreV1Api() throws IOException {
        return new CoreV1Api(k8sApiClient());
    }

    private CustomObjectsApi customObjectsApi() throws IOException {
        return new CustomObjectsApi(k8sApiClient());
    }

    /**
     * Built on first use, not in the constructor: this bean loads on every
     * enablements-workers boot, including Level 1's plain local run with no Kubernetes at
     * all, and in-cluster config resolution fails outside a pod.
     */
    private ApiClient k8sApiClient() throws IOException {
        ApiClient client = k8sApiClient;
        if (client == null) {
            synchronized (this) {
                client = k8sApiClient;
                if (client == null) {
                    client = ClientBuilder.cluster().build();
                    k8sApiClient = client;
                }
            }
        }
        return client;
    }
}
