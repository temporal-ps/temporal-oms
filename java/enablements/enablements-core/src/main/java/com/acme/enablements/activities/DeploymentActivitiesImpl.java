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
 * apps and fulfillment always run as plain Kubernetes Deployments: promotion applies a
 * new versioned Deployment/Service from the generalized template, calls
 * {@code set-current-version} directly, confirms via {@code describe}, then removes
 * prior-version Deployments/Services for that bounded context.
 * <p>
 * processing uses the {@code k8s/processing-versioned} WorkerDeployment CRD for every
 * version except {@code v1} - spec.md's true, unversioned baseline can't be represented
 * under Worker Versioning at all (see {@link #isUnversionedTarget}), so {@code v1} takes
 * the same plain-Deployment path as apps/fulfillment instead, and the CRD is scaled to
 * zero replicas (never deleted) so its pollers and the plain Deployment's are never both
 * live on the {@code processing} task queue at once. Promoting back to {@code v2}+
 * restores the CRD's replica count and removes the stale plain Deployment.
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
    // OrderImpl class actually runs. Kept as its own env var rather than derived from
    // TEMPORAL_WORKER_BUILD_ID: the Safe Fulfillment Handoff workshop deliberately edits
    // an existing vN package in place and deploys it under an unrelated, decoupled build
    // id, so the two must stay independent. Used for the plain-Deployment path (apps,
    // fulfillment, processing v1); the CRD path uses cmd.getBuildId() as the image tag
    // instead (see patchWorkerDeploymentCrd), purely so the Temporal Worker Controller's
    // own computed build-id (image tag + pod spec hash) reads as v2-<hash> rather than
    // latest-<hash> - the deploy scripts tag every processing vN with the same image
    // content, so this never changes which code actually runs.
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
            boolean supportsWorkerDeploymentCrd) {
    }

    private static final Map<String, BoundedContextConfig> BOUNDED_CONTEXTS = Map.of(
            "apps", new BoundedContextConfig(
                    "apps", "temporal-oms-apps", "com.acme.apps.workflows",
                    "ACME_APPS_ORDER_WORKFLOW_CLASS", "temporal-oms/apps-worker", 9092,
                    "temporal-apps-config", "temporal-apps-api-key", false),
            "processing", new BoundedContextConfig(
                    "processing", "temporal-oms-processing", "com.acme.processing.workflows",
                    "ACME_PROCESSING_ORDER_WORKFLOW_CLASS", "temporal-oms/processing-workers", 9082,
                    "temporal-processing-config", "temporal-processing-api-key", true),
            "fulfillment", new BoundedContextConfig(
                    "fulfillment", "temporal-oms-fulfillment", "com.acme.fulfillment.workflows",
                    "ACME_FULFILLMENT_ORDER_WORKFLOW_CLASS", "temporal-oms/fulfillment-workers", 9072,
                    "temporal-fulfillment-config", "temporal-fulfillment-api-key", false));

    @Value("${enablements.deployment.manifest-template:k8s/base/templates/worker-deployment-template.yaml}")
    private String manifestTemplatePath;

    @Value("${enablements.deployment.crd-manifest-template:k8s/base/templates/worker-deployment-crd-template.yaml}")
    private String crdManifestTemplatePath;

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

        // Only processing has a WorkerDeployment CRD to use at all, and only for v2+: v1 is
        // spec.md's true, unversioned baseline, which can't be represented under the CRD
        // (see isUnversionedTarget) - it takes the same plain-Deployment path as apps/fulfillment.
        boolean useCrd = context.supportsWorkerDeploymentCrd() && !isUnversionedTarget(cmd.getBuildId());

        try {
            boolean currentVersionSet;
            if (useCrd) {
                // The Temporal Worker Controller owns this transition: it computes its own
                // build-id from image tag + pod spec hash (confirmed live: patching this CRD
                // with TEMPORAL_WORKER_BUILD_ID=v1 registered as current build-id "latest-f966",
                // not "v1") and runs its own Progressive rollout (ramp/pause steps already in
                // the CRD). Calling setWorkerDeploymentCurrentVersion directly here would target
                // a build-id string that was never actually registered and can never confirm.
                // Patch the pod template (restoring its replica count, in case a prior v1
                // promotion scaled it to zero), then wait for the controller's own rollout.
                patchWorkerDeploymentCrd(cmd, context, workflowClass, replicas);
                currentVersionSet = waitForWorkerDeploymentCrdRollout(cmd.getDeploymentName(), context.k8sNamespace());
            } else {
                applyVersionedDeployment(cmd, context, workflowClass, replicas);
                if (isUnversionedTarget(cmd.getBuildId())) {
                    // The unversioned Spring profile has no deployment-properties block at
                    // all, so this pod never registers a Worker Deployment build-id with
                    // Temporal in the first place - there is no "current version" to set,
                    // and calling setCurrentVersion would just time out forever asking
                    // Temporal to make current a build-id that can never be registered.
                    // The pod coming up as a classic, unversioned poller is success.
                    currentVersionSet = true;
                } else {
                    currentVersionSet = setCurrentVersion(cmd.getDeploymentName(), cmd.getBuildId(), context.temporalNamespace());
                }
                if (currentVersionSet && context.supportsWorkerDeploymentCrd()) {
                    // processing only: keep exactly one pooling mechanism live on the task
                    // queue. Scale the CRD's own pods to zero rather than deleting the CRD -
                    // deleting and recreating it is unnecessary risk when a simple scale down
                    // achieves the same "no CRD-managed pollers" result and promoting back to
                    // v2+ just restores the replica count in patchWorkerDeploymentCrd above.
                    scaleWorkerDeploymentCrd(cmd.getDeploymentName(), context.k8sNamespace(), 0);
                }
            }

            if (currentVersionSet) {
                removeStaleVersions(cmd, context);
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

    /**
     * v1 is spec.md's true, unversioned baseline for every bounded context (apps v1,
     * processing v1, fulfillment v1) - "no Worker Versioning anywhere". Setting
     * {@code deployment-properties.use-versioning: false} does not achieve that: the
     * Temporal Spring Boot starter calls {@code WorkerOptions.setDeploymentOptions(...)}
     * unconditionally whenever a deployment-name/build-id is configured, regardless of
     * {@code useVersioning} (confirmed against temporal-spring-boot-autoconfigure
     * 1.38.0's {@code WorkerOptionsTemplate} source) - the worker still registers a
     * Worker Deployment version with Temporal either way. The only way to get zero
     * Worker Versioning registration is for the {@code deployment-properties} block to
     * be absent from the resolved Spring config entirely, which is what the
     * {@code unversioned} Spring profile's {@code acme.<context>-unversioned.yaml}
     * achieves (verified: activating it produces exactly one worker registration for
     * the v1 class, replacing the versioned worker list, not merging with it).
     */
    private static boolean isUnversionedTarget(String buildId) {
        return "v1".equals(buildId);
    }

    private static String springProfilesActive(String buildId) {
        return isUnversionedTarget(buildId) ? "k8s,unversioned" : "k8s";
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
                .replace("{SPRING_PROFILES_ACTIVE}", springProfilesActive(cmd.getBuildId()))
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
     * Patch the existing WorkerDeployment CRD (processing) in place: new image tag, the
     * env vars that select this version's workflow class and build id, and the replica
     * count (restoring it in case a prior promotion to processing v1 scaled it to zero -
     * see the class Javadoc). A generic CRD has no typed model, so this reads it as a
     * {@link JsonObject}, mutates the same fields {@code kubectl patch}/{@code kubectl
     * set env} used to, and replaces it whole - {@code kubectl set env}'s upsert-by-name
     * behavior for the env list (add if missing, update in place if present, leave every
     * other entry alone) since the CRD's base manifest doesn't predeclare these two env
     * vars.
     */
    private void patchWorkerDeploymentCrd(DeployWorkerVersionRequest cmd, BoundedContextConfig context,
                                           String workflowClass, int replicas) throws ApiException, IOException {
        String resourceName = cmd.getDeploymentName() + "-workers";
        String image = context.imageRepository() + ":" + cmd.getBuildId();

        JsonObject workerDeployment = readOrCreateWorkerDeploymentCrd(resourceName, context, image);

        JsonObject spec = workerDeployment.getAsJsonObject("spec");
        spec.addProperty("replicas", replicas);
        JsonObject container = spec
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
     * Read the WorkerDeployment CRD, or create it from {@code crdManifestTemplatePath}
     * if it doesn't exist. The deploy scripts provision this CRD once at bring-up and
     * never delete it (only scale it), so a missing CRD here means something removed it
     * out of band - a promotion must recover from that instead of failing outright.
     */
    private JsonObject readOrCreateWorkerDeploymentCrd(String resourceName, BoundedContextConfig context, String image)
            throws ApiException, IOException {
        try {
            Object raw = customObjectsApi().getNamespacedCustomObject(
                    WORKER_DEPLOYMENT_GROUP, WORKER_DEPLOYMENT_VERSION, context.k8sNamespace(),
                    WORKER_DEPLOYMENT_PLURAL, resourceName).execute();
            return GSON.toJsonTree(raw).getAsJsonObject();
        } catch (ApiException e) {
            if (e.getCode() != 404) {
                throw e;
            }
            logger.warn("WorkerDeployment CRD {}/{} not found; creating it from {}",
                    context.k8sNamespace(), resourceName, crdManifestTemplatePath);
            String manifest = Files.readString(Paths.get(crdManifestTemplatePath))
                    .replace("{NAME}", resourceName)
                    .replace("{NAMESPACE}", context.k8sNamespace())
                    .replace("{REPLICAS}", "0")
                    .replace("{IMAGE}", image)
                    .replace("{MANAGEMENT_PORT}", String.valueOf(context.managementPort()))
                    .replace("{DEPLOYMENT_NAME}", context.temporalNamespace())
                    .replace("{CONFIGMAP_NAME}", context.configMapName());
            Object body = new org.yaml.snakeyaml.Yaml().load(manifest);
            Object created = customObjectsApi().createNamespacedCustomObject(
                    WORKER_DEPLOYMENT_GROUP, WORKER_DEPLOYMENT_VERSION, context.k8sNamespace(),
                    WORKER_DEPLOYMENT_PLURAL, body).fieldManager("enablements-workers").execute();
            return GSON.toJsonTree(created).getAsJsonObject();
        }
    }

    /**
     * Scale the WorkerDeployment CRD's replica count without touching anything else -
     * used only to take it to zero pods while processing v1 (unversioned, plain
     * Deployment) is active, per the class Javadoc. Never deletes the CRD.
     */
    private void scaleWorkerDeploymentCrd(String deploymentName, String namespace, int replicas) {
        String resourceName = deploymentName + "-workers";
        try {
            Object raw = customObjectsApi().getNamespacedCustomObject(
                    WORKER_DEPLOYMENT_GROUP, WORKER_DEPLOYMENT_VERSION, namespace,
                    WORKER_DEPLOYMENT_PLURAL, resourceName).execute();
            JsonObject workerDeployment = GSON.toJsonTree(raw).getAsJsonObject();
            workerDeployment.getAsJsonObject("spec").addProperty("replicas", replicas);
            customObjectsApi().replaceNamespacedCustomObject(
                    WORKER_DEPLOYMENT_GROUP, WORKER_DEPLOYMENT_VERSION, namespace,
                    WORKER_DEPLOYMENT_PLURAL, resourceName, workerDeployment).fieldManager("enablements-workers").execute();
        } catch (Exception e) {
            logger.warn("Failed to scale {} WorkerDeployment CRD to {} replicas (non-fatal)",
                    deploymentName, replicas, e);
        }
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
