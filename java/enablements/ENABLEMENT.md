# Scenario: Safely Promoting Processing Workers Under Load

This walkthrough demonstrates safely deploying a new version of the `processing` workers
while sustained load is running — without dropping in-flight workflows.

---

## Phase 1: Verify a Pristine Environment

**Goal:** Confirm both the local cluster and cloud namespaces are clean before starting.

1. Confirm KinD has no existing `temporal-oms` workloads running
2. Confirm the cloud namespaces have no active workflows:
   - Cloud OMS Apps Namespace
   - Cloud OMS Processing Namespace

---

## Phase 2: Deploy and Start

**Goal:** Get the full stack running at `v1`.

3. Deploy `temporal-oms` to KinD:
   ```shell
   # from the repo root
   ./scripts/demo-up.sh
   ```

4. Open `k9s` and note the `v1` prefix in the worker `buildID` — this is the current deployment version.

5. Start a local Temporal Server (if not already running):
   ```shell
   temporal server start-dev
   ```

6. Start the `enablements-workers` application — this worker connects to **local** Temporal:
   ```shell
   # from java/enablements
   ./gradlew bootRun
   ```

---

## Phase 3: Generate Sustained Load

**Goal:** Start the load generator so workflows are actively running during the promotion.

7a. Tunnel from the host computer to the KinD cluster
```shell
./scripts/tunnel.sh
```

7b. Start the `WorkerVersionEnablement` workflow:
   ```shell
   temporal workflow start \
       --task-queue enablements \
       --type WorkerVersionEnablement \
       --workflow-id "enablement-demo-2026-03-22" \
       --namespace default \
       --input '{
         "enablementId": "demo-2026-03-22",
         "orderCount": 20,
         "submitRatePerMin": 5,
         "timeout": "600s",
         "orderIdSeed": "demo",
         "scenarioWeights": [
           {"scenario": "NORMAL", "weight": 85},
           {"scenario": "PAYMENT_BEFORE_COMMERCE", "weight": 5},
           {"scenario": "MISSING_COMMERCE_EVENT", "weight": 5},
           {"scenario": "MISSING_PAYMENT_EVENT", "weight": 5}
         ]
       }' \
       --input-meta 'encoding=json/protobuf'
   ```
   `orderIdSeed` is now just a naming prefix with no behavioral meaning;
   the old `"orderIdSeed": "invalid"` string-match hack is removed.
   `scenarioWeights` drives the delivery-scenario mix (see
   `SPECS/commerce-payments-apps/spec.md` and
   `SPECS/enablements/load-generation/spec.md`); omit it to use the
   default mostly-`NORMAL` mix.

8. Verify traffic is flowing in the cloud UIs:
   - Cloud OMS Apps Namespace — confirm there is **no** `DeploymentVersion` field (unversioned)
   - Cloud OMS Processing Namespace — confirm `DeploymentVersion` shows **`v1`**

---

## Phase 4: Promote to v2

**Goal:** Roll out new processing workers and observe the progressive promotion.

9. Deploy the `v2` processing workers:
   ```shell
   VERSION=v2 ./scripts/deploy-processing-workers
   ```
   > Walk through the `kubectl patch` command inside the script to explain how the deployment is triggered.

10. In `k9s`, watch the new `processing-worker:v2-{hash}` pod come up alongside `v1`.

11. In **Cloud OMS Processing Namespace**
    Observe `DeploymentVersion` begin to show **`v2`** on new workflows.
    - Discuss the **`rollout: progressive`** ramp strategy — new workflows route to `v2`, existing ones stay on `v1`
    - Discuss the **`sunset`** strategy — `v1` pods remain until all pinned workflows complete

---

## Phase 5: Migrate Long-Running Workflows

**Goal:** Move the long-lived `support-team` workflow off `v1` so those pods can be reclaimed.

12. Notice that `support-team` is still running on **`v1`**. Because it is long-lived, it will hold `v1` pods
    alive indefinitely under the default `pinned` versioning behavior.

13. Upgrade `support-team` to auto-upgrade so it moves to `v2`:
    ```shell
    temporal workflow update-options \
        --workflow-id "support-team" \
        --versioning-override-behavior auto_upgrade \
        --env fde-oms-processing
    ```

14. In **Cloud OMS Processing Namespace**,
    confirm `support-team` now shows `DeploymentVersion` = **`v2`**.

15. Back in `k9s`, observe that `processing-worker:v1-{hash}` is now **gone** — the pod was reclaimed
    once no workflows remained pinned to it.
```yaml
  sunset:
    scaledownDelay: 30s
    deleteDelay: 120s
```