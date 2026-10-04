# Codespaces k3d Remote Runbook

**Status:** remote validation guide, not attendee workshop setup.
**Last updated:** 2026-04-30

This runbook turns the Codespaces support planning spec into a concrete, repeatable remote
validation path for `scripts/k3d/*`. It intentionally keeps the k3d path separate from the KinD
path and does not change the recommended attendee flow, which remains local processes without
Kubernetes.

## Guardrails

- Use this only in a dedicated instructor/maintainer Codespace.
- Do not ask workshop attendees to run this path until it has passed repeatedly from fresh
  Codespaces.
- Start with an 8 core / 32 GB / 64 GB Codespace when available. Treat smaller machines as a
  resource test, not the recommended path.
- Keep `scripts/kind/*` and `scripts/k3d/*` as separate runners.
- Use `OVERLAY=local` for this runbook unless explicitly validating Temporal Cloud.
- Bind the local Temporal dev server to `0.0.0.0` so k3d pods can attempt to reach it through the
  Docker host gateway.
- Capture failures instead of improvising broad fixes during a live workshop.

## Create Or Open The Codespace

Use the browser flow first. It is the least surprising path for a first run.

1. Open the repository on GitHub.
2. Select the target branch from the branch dropdown.
3. Click **Code**.
4. Open the **Codespaces** tab.
5. Use the dropdown in that tab and choose **New with options**.
6. Select the target branch.
7. Select the dev container configuration if GitHub asks.
8. Select the largest available machine type, ideally 8 core / 32 GB or larger.
9. Click **Create codespace**.
10. Wait for the VS Code web editor to open.
11. In the web editor, open a terminal with **Terminal** -> **New Terminal**.

If a suitable Codespace already exists:

1. Go to `https://github.com/codespaces`.
2. Find the Codespace for this repository and branch.
3. Open it in the browser or VS Code.
4. Stop and create a fresh Codespace if you are trying to validate first-run behavior.

GitHub CLI alternative:

```bash
gh auth login
gh codespace create -r OWNER/fde-temporal-oms -b BRANCH
gh codespace code --web
```

The CLI will prompt for repository, branch, devcontainer, and machine type if you do not provide
all flags. Prefer the browser flow for this runbook because it makes the selected machine type and
billing owner visible before creation.

If the **Codespaces** tab or machine type is missing, check repository/organization Codespaces
policy, billing/spending limit, and your repository permissions before continuing.

## Expected Starting Point

Open a fresh Codespace on the target workshop branch, then use the integrated terminal.

Confirm you are actually remote in Codespaces:

```bash
cd /workspaces/fde-temporal-oms
printf 'CODESPACES=%s\n' "${CODESPACES:-}"
pwd
```

Expected:

- `CODESPACES=true`
- working directory is `/workspaces/fde-temporal-oms`

If those are not true, stop and record that the run was not Codespaces validation.

## Phase 0: Preflight

Check machine capacity:

```bash
nproc
free -h
df -h .
```

Check required tools:

```bash
docker --version
docker ps
k3d version
kubectl version --client
helm version
java -version
mvn --version
temporal -v
```

Expected tool shape:

- Docker daemon is reachable from the Codespace.
- k3d is available.
- `kubectl` includes Kustomize support.
- Helm is available.
- Java and Maven both report Java 21.
- Temporal CLI supports Worker Deployment commands.

If a required tool is absent, record it as a devcontainer/tooling blocker. Do not turn this
validation run into a long manual tool-install session unless the goal is specifically to discover
devcontainer requirements.

## Phase 1: Start Local Temporal

Start Temporal on the Codespace host and bind it to all interfaces:

```bash
mkdir -p .workshop/logs .workshop/run
temporal server start-dev --ip 0.0.0.0 --ui-ip 0.0.0.0 > .workshop/logs/temporal-dev.log 2>&1 &
echo $! > .workshop/run/temporal-dev.pid
```

Wait for the frontend:

```bash
temporal --address 127.0.0.1:7233 operator cluster health
```

Create local namespaces, Nexus endpoints, search attributes, and baseline Worker Deployment current
versions:

```bash
./scripts/setup-temporal-namespaces.sh
```

Quick Temporal checks:

```bash
temporal --address 127.0.0.1:7233 operator namespace list
temporal --address 127.0.0.1:7233 worker deployment list --namespace processing
```

## Phase 2: Deploy k3d Demo

Run the existing k3d demo flow:

```bash
OVERLAY=local ./scripts/k3d/demo-up.sh
```

This should:

1. create or reuse the `temporal-oms` k3d cluster
2. write `/tmp/k3d-config.yaml`
3. disable bundled K3s Traefik
4. create OMS namespaces
5. install cert-manager
6. install Temporal Worker Controller CRDs and Helm chart
7. install the repo Traefik manifest
8. run `mvn clean install -DskipTests`
9. build OMS Docker images
10. import images with `k3d image import`
11. apply the local Kustomize overlay
12. apply the processing `WorkerDeployment` overlay

If the command fails, capture the failing phase and continue to the diagnostics section before
tearing down.

## Phase 3: Verify Kubernetes And TWC State

Set kubeconfig for manual checks:

```bash
export KUBECONFIG=/tmp/k3d-config.yaml
```

Run the repo status script:

```bash
./scripts/k3d/status.sh
```

Inspect cluster state:

```bash
kubectl get pods -A
kubectl get workerdeployments -A
kubectl get connections -A
kubectl get events -A --sort-by=.lastTimestamp
```

Expected minimum success:

- cert-manager pods are running.
- `temporal-worker-controller-manager` is running.
- repo Traefik pod is running.
- OMS API and worker pods are running or ready.
- `temporal-oms-processing/processing-workers` exists as a `WorkerDeployment`.
- processing WorkerDeployment eventually reports `ConnectionHealthy=True`, `Ready=True`, and
  `RolloutComplete=True`.

Check Temporal's view of the processing worker deployment:

```bash
temporal --address localhost:7233 worker deployment list --namespace processing
temporal --address localhost:7233 worker deployment describe --namespace processing --name processing
```

## Phase 4: Verify k3d Pods Can Reach Host Temporal

The local overlay currently points pods at `host.docker.internal:7233`. Validate that exact path from
inside the k3d cluster:

```bash
kubectl run temporal-netcheck \
  -n temporal-oms-processing \
  --rm \
  --restart=Never \
  --image=busybox:1.36 \
  --command -- sh -c '
set +e
echo "== host.docker.internal =="
nslookup host.docker.internal
nc -vz -w 5 host.docker.internal 7233
echo "== host.k3d.internal =="
nslookup host.k3d.internal
nc -vz -w 5 host.k3d.internal 7233
'
```

Record the result:

- If `host.docker.internal:7233` works, keep the existing local overlay.
- If `host.docker.internal` fails but `host.k3d.internal:7233` works, the safest follow-up is a
  k3d/Codespaces-specific patch that does not alter the KinD path.
- If neither host alias works, prefer Temporal Cloud or an in-cluster Temporal dev server for the
  remote k8s demo rather than changing the attendee path.

## Phase 5: Verify API Access

Start the existing k3d tunnel in the background:

```bash
./scripts/k3d/tunnel.sh > .workshop/logs/k3d-tunnel.log 2>&1 &
echo $! > .workshop/run/k3d-tunnel.pid
```

Check local API health from the Codespace terminal:

```bash
curl -fsS http://localhost:8080/api/actuator/health
curl -fsS http://localhost:8050/actuator/health
```

If using the Codespaces Ports tab, keep forwarded ports private by default:

- `8233` Temporal UI
- `8080` Apps API
- `8050` Enablements API
- `8070` Processing API
- `7233` Temporal gRPC only if external CLI access is needed

## Diagnostics To Capture On Failure

Create an evidence directory:

```bash
mkdir -p .workshop/evidence/k3d-codespaces
```

Capture high-signal state:

```bash
date > .workshop/evidence/k3d-codespaces/date.txt
printf 'CODESPACES=%s\n' "${CODESPACES:-}" > .workshop/evidence/k3d-codespaces/environment.txt
nproc > .workshop/evidence/k3d-codespaces/cpu.txt
free -h > .workshop/evidence/k3d-codespaces/memory.txt
df -h . > .workshop/evidence/k3d-codespaces/disk.txt
docker --version > .workshop/evidence/k3d-codespaces/docker-version.txt
k3d version > .workshop/evidence/k3d-codespaces/k3d-version.txt
kubectl version --client > .workshop/evidence/k3d-codespaces/kubectl-version.txt
helm version > .workshop/evidence/k3d-codespaces/helm-version.txt
java -version > .workshop/evidence/k3d-codespaces/java-version.txt 2>&1
mvn --version > .workshop/evidence/k3d-codespaces/maven-version.txt
temporal -v > .workshop/evidence/k3d-codespaces/temporal-version.txt
```

If Kubernetes came up:

```bash
export KUBECONFIG=/tmp/k3d-config.yaml
kubectl get pods -A -o wide > .workshop/evidence/k3d-codespaces/pods.txt
kubectl get workerdeployments -A -o yaml > .workshop/evidence/k3d-codespaces/wds.yaml
kubectl get connections -A -o yaml > .workshop/evidence/k3d-codespaces/connections.yaml
kubectl get events -A --sort-by=.lastTimestamp > .workshop/evidence/k3d-codespaces/events.txt
kubectl describe pods -A > .workshop/evidence/k3d-codespaces/pod-describes.txt
kubectl logs -n temporal-worker-controller-system deploy/temporal-worker-controller-manager --tail=300 > .workshop/evidence/k3d-codespaces/twc-controller.log
kubectl logs -n temporal-oms-processing -l app=processing-workers --tail=300 > .workshop/evidence/k3d-codespaces/processing-workers.log
kubectl logs -n temporal-oms-apps -l app=apps-api --tail=300 > .workshop/evidence/k3d-codespaces/apps-api.log
kubectl logs -n temporal-oms-enablements -l app=enablements-api --tail=300 > .workshop/evidence/k3d-codespaces/enablements-api.log
```

Common blocker categories to record:

| Blocker | What to capture | Likely next action |
|---|---|---|
| Docker unavailable | `docker ps` error | Add/fix devcontainer Docker support |
| Missing k3d/kubectl/helm/temporal | tool version command output | Add tool to devcontainer |
| Under-sized Codespace | `nproc`, `free -h`, `df -h .` | rerun on 8 core / 32 GB |
| Maven uses Java < 21 | `mvn --version` | fix `JAVA_HOME` or asdf setup |
| cert-manager/TWC install fails | Helm/kubectl error and events | retry only after confirming network and CRDs |
| image build/import fails | Docker build or `k3d image import` output | check disk and Docker daemon |
| pods cannot reach Temporal | netcheck results and app logs | test `host.k3d.internal`; consider Temporal Cloud |
| WorkerDeployment never becomes ready | WorkerDeployment YAML and controller logs | inspect Temporal connection and worker pollers |
| health endpoints fail | service logs and tunnel log | inspect pod readiness and port-forward state |

## Cleanup

Stop the tunnel if it was started:

```bash
if [ -f .workshop/run/k3d-tunnel.pid ]; then
  kill "$(cat .workshop/run/k3d-tunnel.pid)" 2>/dev/null || true
  rm -f .workshop/run/k3d-tunnel.pid
fi
```

Tear down k3d:

```bash
HARD=1 ./scripts/k3d/demo-down.sh
```

Stop local Temporal:

```bash
if [ -f .workshop/run/temporal-dev.pid ]; then
  kill "$(cat .workshop/run/temporal-dev.pid)" 2>/dev/null || true
  rm -f .workshop/run/temporal-dev.pid
fi
```

Confirm nothing long-running remains from this run:

```bash
pgrep -af 'temporal server start-dev|kubectl port-forward' || true
k3d cluster list
```

## Report Template

After each Codespaces run, add a dated note to `specs/workshop/codespaces-support/spec.md` with:

- Codespace machine size and branch.
- Tool versions.
- Whether Temporal dev server started on `0.0.0.0`.
- Whether `host.docker.internal:7233` worked from pods.
- Whether `host.k3d.internal:7233` was tested and worked.
- Whether `OVERLAY=local ./scripts/k3d/demo-up.sh` completed.
- Final `WorkerDeployment` conditions.
- API health results.
- Exact blockers and small fixes made.
- Whether the environment was fully cleaned up.
