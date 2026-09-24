#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXERCISE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
STATE_ROOT="$ROOT_DIR/.workshop/safe-fulfillment-handoff"
RUN_DIR="$STATE_ROOT/run"
LOG_DIR="$STATE_ROOT/logs"
STATE_DIR="$STATE_ROOT/state"
STARTUP_TIMEOUT="${LOCAL_STARTUP_TIMEOUT:-120}"

TEMPORAL_ADDRESS="${TEMPORAL_ADDRESS:-127.0.0.1:7233}"
TEMPORAL_BIND_IP="${TEMPORAL_BIND_IP:-0.0.0.0}"
TEMPORAL_UI_BIND_IP="${TEMPORAL_UI_BIND_IP:-0.0.0.0}"
TEMPORAL_UI_HOST="${TEMPORAL_UI_HOST:-127.0.0.1}"
TEMPORAL_UI_PORT="${TEMPORAL_UI_PORT:-8233}"

TEMPORAL_APPS_NAMESPACE="${TEMPORAL_APPS_NAMESPACE:-apps}"
TEMPORAL_PROCESSING_NAMESPACE="${TEMPORAL_PROCESSING_NAMESPACE:-processing}"
TEMPORAL_FULFILLMENT_NAMESPACE="${TEMPORAL_FULFILLMENT_NAMESPACE:-fulfillment}"
TEMPORAL_ENABLEMENTS_NAMESPACE="${TEMPORAL_ENABLEMENTS_NAMESPACE:-default}"

service_names=(
  temporal-server
  apps-api
  apps-workers-v1
  processing-api
  processing-workers-v1
  enablements-api
  enablements-workers
  processing-workers-v2
  fulfillment-workers
  python-fulfillment-worker
  apps-workers-v2
)

stop_order=(
  apps-workers-v2
  python-fulfillment-worker
  fulfillment-workers
  processing-workers-v2
  enablements-workers
  processing-workers-v1
  processing-api
  apps-workers-v1
  apps-api
  enablements-api
  temporal-server
)

init_dirs() {
  mkdir -p "$RUN_DIR" "$LOG_DIR" "$STATE_DIR"
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || die "Missing required command '$command_name'."
}

load_dotenv() {
  if [[ -f "$ROOT_DIR/.env.local" ]]; then
    set -a
    # shellcheck disable=SC1091
    . "$ROOT_DIR/.env.local"
    set +a
  fi
}

configure_local_environment() {
  export TEMPORAL_ADDRESS
  export TEMPORAL_APPS_ADDRESS="${TEMPORAL_APPS_ADDRESS:-$TEMPORAL_ADDRESS}"
  export TEMPORAL_PROCESSING_ADDRESS="${TEMPORAL_PROCESSING_ADDRESS:-$TEMPORAL_ADDRESS}"
  export TEMPORAL_FULFILLMENT_ADDRESS="${TEMPORAL_FULFILLMENT_ADDRESS:-$TEMPORAL_ADDRESS}"
  export TEMPORAL_ENABLEMENTS_ADDRESS="${TEMPORAL_ENABLEMENTS_ADDRESS:-$TEMPORAL_ADDRESS}"

  export TEMPORAL_APPS_NAMESPACE
  export TEMPORAL_PROCESSING_NAMESPACE
  export TEMPORAL_FULFILLMENT_NAMESPACE
  export TEMPORAL_ENABLEMENTS_NAMESPACE

  export TEMPORAL_APPS_API_KEY="${TEMPORAL_APPS_API_KEY:-}"
  export TEMPORAL_PROCESSING_API_KEY="${TEMPORAL_PROCESSING_API_KEY:-}"
  export TEMPORAL_FULFILLMENT_API_KEY="${TEMPORAL_FULFILLMENT_API_KEY:-}"
  export TEMPORAL_ENABLEMENTS_API_KEY="${TEMPORAL_ENABLEMENTS_API_KEY:-}"

  export ENABLEMENTS_API_BASE_URL="${ENABLEMENTS_API_BASE_URL:-http://localhost:8050}"
  export ENVIRONMENT="${ENVIRONMENT:-local}"
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
}

prepare_environment() {
  init_dirs
  load_dotenv
  configure_local_environment
}

pid_file_for() {
  echo "$RUN_DIR/$1.pid"
}

log_file_for() {
  echo "$LOG_DIR/$1.log"
}

pid_is_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

service_pid() {
  local pid_file
  pid_file="$(pid_file_for "$1")"
  [[ -f "$pid_file" ]] && cat "$pid_file" 2>/dev/null || true
}

service_is_running() {
  local pid
  pid="$(service_pid "$1")"
  pid_is_running "$pid"
}

tail_service_log() {
  local name="$1"
  local log
  log="$(log_file_for "$name")"
  if [[ -f "$log" ]]; then
    echo ""
    echo "Last 80 lines from $log:"
    tail -n 80 "$log" || true
  fi
}

start_service() {
  local name="$1"
  shift
  local log pid_file pid
  log="$(log_file_for "$name")"
  pid_file="$(pid_file_for "$name")"

  if service_is_running "$name"; then
    echo "$name already running as pid $(service_pid "$name")"
    return 0
  fi

  rm -f "$pid_file"
  echo "Starting $name ..."
  : >"$log"
  (
    cd "$ROOT_DIR"
    exec "$@"
  ) >"$log" 2>&1 &

  pid=$!
  echo "$pid" >"$pid_file"

  sleep 1
  if ! pid_is_running "$pid"; then
    local status=0
    wait "$pid" || status=$?
    tail_service_log "$name"
    die "$name exited during startup with status $status"
  fi
}

stop_service() {
  local name="$1"
  local pid_file pid waited
  pid_file="$(pid_file_for "$name")"
  [[ -f "$pid_file" ]] || return 0

  pid="$(cat "$pid_file" 2>/dev/null || true)"
  if ! pid_is_running "$pid"; then
    echo "$name is not running"
    rm -f "$pid_file"
    return 0
  fi

  echo "Stopping $name (pid $pid) ..."
  kill "$pid" >/dev/null 2>&1 || true

  waited=0
  while pid_is_running "$pid" && [[ "$waited" -lt 20 ]]; do
    sleep 1
    waited=$((waited + 1))
  done

  if pid_is_running "$pid"; then
    echo "  $name did not stop after 20s; sending SIGKILL"
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi

  rm -f "$pid_file"
}

wait_http() {
  local name="$1"
  local url="$2"
  local started pid
  started="$SECONDS"

  echo "Waiting for $name at $url ..."
  while (( SECONDS - started < STARTUP_TIMEOUT )); do
    pid="$(service_pid "$name")"
    if ! pid_is_running "$pid"; then
      tail_service_log "$name"
      die "$name exited before $url became ready"
    fi

    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      echo "  $name ready"
      return 0
    fi
    sleep 2
  done

  tail_service_log "$name"
  die "$name timed out after ${STARTUP_TIMEOUT}s waiting for $url"
}

wait_log() {
  local name="$1"
  local pattern="$2"
  local started pid log
  started="$SECONDS"
  log="$(log_file_for "$name")"

  echo "Waiting for $name startup log ..."
  while (( SECONDS - started < STARTUP_TIMEOUT )); do
    pid="$(service_pid "$name")"
    if ! pid_is_running "$pid"; then
      tail_service_log "$name"
      die "$name exited before startup completed"
    fi

    if grep -E "$pattern" "$log" >/dev/null 2>&1; then
      echo "  $name ready"
      return 0
    fi
    sleep 2
  done

  tail_service_log "$name"
  die "$name timed out after ${STARTUP_TIMEOUT}s waiting for log pattern: $pattern"
}

temporal_cli() {
  temporal --disable-config-file --disable-config-env --address "$TEMPORAL_ADDRESS" "$@"
}

wait_temporal() {
  local started
  started="$SECONDS"

  echo "Waiting for Temporal at $TEMPORAL_ADDRESS ..."
  while (( SECONDS - started < STARTUP_TIMEOUT )); do
    if temporal_cli operator cluster health --command-timeout 2s >/dev/null 2>&1; then
      echo "  Temporal ready"
      return 0
    fi
    sleep 2
  done

  tail_service_log temporal-server
  die "Temporal did not become ready at $TEMPORAL_ADDRESS"
}

start_temporal() {
  require_command temporal

  if temporal_cli operator cluster health --command-timeout 2s >/dev/null 2>&1; then
    echo "Temporal already reachable at $TEMPORAL_ADDRESS"
    return 0
  fi

  start_service temporal-server temporal server start-dev \
    --ip "$TEMPORAL_BIND_IP" \
    --port 7233 \
    --ui-ip "$TEMPORAL_UI_BIND_IP" \
    --ui-port "$TEMPORAL_UI_PORT"
  wait_temporal
}

setup_namespaces() {
  local log="$LOG_DIR/temporal-setup.log"
  echo "Setting up Temporal namespaces and Nexus endpoints ..."
  if ! SKIP_VERSION_REGISTRATION=0 "$ROOT_DIR/scripts/setup-temporal-namespaces.sh" >"$log" 2>&1; then
    tail -n 120 "$log" >&2 || true
    die "Temporal namespace setup failed. See $log"
  fi
}

run_maven() {
  require_command mvn
  (
    cd "$ROOT_DIR/java"
    if [[ "${CLEAN_BUILD:-0}" == "1" ]]; then
      mvn clean "$@"
    else
      mvn "$@"
    fi
  )
}

build_all_java() {
  if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "Skipping Java build because SKIP_BUILD=1"
    return 0
  fi
  run_maven -DskipTests install
}

build_processing_worker() {
  if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "Skipping processing worker build because SKIP_BUILD=1"
    return 0
  fi
  run_maven -pl processing/processing-workers -am -DskipTests install
}

build_apps_worker() {
  if [[ "${SKIP_BUILD:-0}" == "1" ]]; then
    echo "Skipping apps worker build because SKIP_BUILD=1"
    return 0
  fi
  run_maven -pl apps/apps-workers -am -DskipTests install
}

set_current_version() {
  local deployment_name="$1"
  local build_id="$2"
  local namespace="$3"
  local started="$SECONDS"

  echo "Setting $deployment_name current version to $build_id ..."
  while (( SECONDS - started < STARTUP_TIMEOUT )); do
    if temporal_cli worker deployment set-current-version \
      --deployment-name "$deployment_name" \
      --build-id "$build_id" \
      --namespace "$namespace" \
      --yes; then
      return 0
    fi
    sleep 2
  done

  die "Timed out setting $deployment_name current version to $build_id in namespace $namespace"
}

describe_deployment() {
  local deployment_name="$1"
  local namespace="$2"
  temporal_cli worker deployment describe \
    --name "$deployment_name" \
    --namespace "$namespace"
}

print_runtime_summary() {
  cat <<EOF

Exercise runtime:
  Temporal UI: http://$TEMPORAL_UI_HOST:$TEMPORAL_UI_PORT
  Logs:        $LOG_DIR
  PIDs:        $RUN_DIR

Useful commands:
  ./workshop/safe-fulfillment-handoff/scripts/status.sh
  ./workshop/safe-fulfillment-handoff/scripts/logs.sh <service-name>
  ./workshop/safe-fulfillment-handoff/scripts/stop.sh
EOF
}
