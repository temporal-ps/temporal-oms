#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_DIR="$ROOT_DIR/.workshop/run"
LOG_DIR="$ROOT_DIR/.workshop/logs"
STARTUP_TIMEOUT="${LOCAL_STARTUP_TIMEOUT:-120}"

TEMPORAL_ADDRESS="${TEMPORAL_ADDRESS:-127.0.0.1:7233}"
TEMPORAL_UI_HOST="${TEMPORAL_UI_HOST:-127.0.0.1}"
TEMPORAL_UI_PORT="${TEMPORAL_UI_PORT:-8233}"

UP_SUCCEEDED=0

service_names=(
  enablements-api
  fulfillment-api
  processing-api
  apps-api
  apps-workers
  processing-workers
  fulfillment-workers
  enablements-workers
  python-fulfillment-worker
)

die() {
  echo "ERROR: $*" >&2
  exit 1
}

log_tail() {
  local name="$1"
  local log="$LOG_DIR/$name.log"
  if [[ -f "$log" ]]; then
    echo ""
    echo "Last 80 lines from $log:"
    tail -n 80 "$log" || true
  fi
}

fail_service() {
  local name="$1"
  shift
  echo "ERROR: $name failed: $*" >&2
  log_tail "$name"
  exit 1
}

cleanup_on_failure() {
  local status=$?
  if [[ "$status" -ne 0 && "$UP_SUCCEEDED" -eq 0 ]]; then
    echo ""
    echo "local-up failed; stopping services started by this run..."
    "$SCRIPT_DIR/local-down.sh" --quiet || true
  fi
}
trap cleanup_on_failure EXIT

require_command() {
  local command_name="$1"
  command -v "$command_name" >/dev/null 2>&1 || die "Missing required command '$command_name'. Install it before running scripts/local-up.sh."
}

require_file() {
  local file="$1"
  local hint="$2"
  [[ -f "$file" ]] || die "Missing required file: $file
$hint"
}

load_dotenv() {
  if [[ ! -f "$ROOT_DIR/.env.local" && -f "$ROOT_DIR/.env.codespaces" ]]; then
    cp "$ROOT_DIR/.env.codespaces" "$ROOT_DIR/.env.local"
  fi

  if [[ -f "$ROOT_DIR/.env.local" ]]; then
    set -a
    # shellcheck disable=SC1091
    . "$ROOT_DIR/.env.local"
    set +a
  fi
}

configure_local_environment() {
  TEMPORAL_ADDRESS="${TEMPORAL_ADDRESS:-127.0.0.1:7233}"
  TEMPORAL_UI_HOST="${TEMPORAL_UI_HOST:-127.0.0.1}"
  TEMPORAL_UI_PORT="${TEMPORAL_UI_PORT:-8233}"

  export TEMPORAL_ADDRESS
  export TEMPORAL_APPS_ADDRESS="$TEMPORAL_ADDRESS"
  export TEMPORAL_PROCESSING_ADDRESS="$TEMPORAL_ADDRESS"
  export TEMPORAL_FULFILLMENT_ADDRESS="$TEMPORAL_ADDRESS"
  export TEMPORAL_ENABLEMENTS_ADDRESS="$TEMPORAL_ADDRESS"

  export TEMPORAL_APPS_NAMESPACE="${TEMPORAL_APPS_NAMESPACE:-apps}"
  export TEMPORAL_PROCESSING_NAMESPACE="${TEMPORAL_PROCESSING_NAMESPACE:-processing}"
  export TEMPORAL_FULFILLMENT_NAMESPACE="${TEMPORAL_FULFILLMENT_NAMESPACE:-fulfillment}"
  export TEMPORAL_ENABLEMENTS_NAMESPACE="${TEMPORAL_ENABLEMENTS_NAMESPACE:-default}"

  export TEMPORAL_APPS_API_KEY=""
  export TEMPORAL_PROCESSING_API_KEY=""
  export TEMPORAL_FULFILLMENT_API_KEY=""
  export TEMPORAL_ENABLEMENTS_API_KEY=""

  export ENABLEMENTS_API_BASE_URL="${ENABLEMENTS_API_BASE_URL:-http://localhost:8050}"
  export ENVIRONMENT="${ENVIRONMENT:-local}"
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
}

pid_is_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

ensure_no_existing_run() {
  local name pid_file pid
  for name in "${service_names[@]}"; do
    pid_file="$RUN_DIR/$name.pid"
    if [[ -f "$pid_file" ]]; then
      pid="$(cat "$pid_file" 2>/dev/null || true)"
      if pid_is_running "$pid"; then
        die "$name already appears to be running as pid $pid. Run ./scripts/local-down.sh first."
      fi
      rm -f "$pid_file"
    fi
  done
}

port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  else
    (echo >"/dev/tcp/127.0.0.1/$port") >/dev/null 2>&1
  fi
}

port_owner() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | tail -n +2 || true
  else
    echo "Port $port is listening."
  fi
}

check_port() {
  local port="$1"
  local owner="$2"
  if port_in_use "$port"; then
    echo "Port $port needed by $owner is already in use:"
    port_owner "$port"
    return 1
  fi
}

check_required_ports() {
  local failed=0

  check_port 8050 "enablements-api" || failed=1
  check_port 8060 "fulfillment-api" || failed=1
  check_port 8061 "fulfillment-workers" || failed=1
  check_port 8070 "processing-api" || failed=1
  check_port 8071 "processing-workers" || failed=1
  check_port 8080 "apps-api" || failed=1
  check_port 8081 "apps-workers" || failed=1
  check_port 9050 "enablements-api management" || failed=1
  check_port 9052 "enablements-workers management override" || failed=1
  check_port 9071 "fulfillment-api management" || failed=1
  check_port 9072 "fulfillment-workers management" || failed=1
  check_port 9081 "processing-api management" || failed=1
  check_port 9082 "processing-workers management" || failed=1
  check_port 9091 "apps-api management" || failed=1
  check_port 9092 "apps-workers management" || failed=1

  [[ "$failed" -eq 0 ]] || die "One or more required ports are busy. Stop the process above or change the service port before retrying."
}

start_service() {
  local name="$1"
  shift
  local log="$LOG_DIR/$name.log"
  local pid_file="$RUN_DIR/$name.pid"

  echo "Starting $name ..."
  : >"$log"
  (
    cd "$ROOT_DIR"
    exec "$@"
  ) >"$log" 2>&1 &

  local pid=$!
  echo "$pid" >"$pid_file"

  sleep 1
  if ! pid_is_running "$pid"; then
    local status=0
    wait "$pid" || status=$?
    fail_service "$name" "process exited during startup with status $status"
  fi
}

wait_http() {
  local name="$1"
  local url="$2"
  local pid_file="$RUN_DIR/$name.pid"
  local started="$SECONDS"
  local pid

  echo "Waiting for $name at $url ..."
  while (( SECONDS - started < STARTUP_TIMEOUT )); do
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if ! pid_is_running "$pid"; then
      local status=0
      wait "$pid" || status=$?
      fail_service "$name" "process exited before $url became ready; status $status"
    fi

    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      echo "  $name ready"
      return 0
    fi
    sleep 2
  done

  fail_service "$name" "timed out after ${STARTUP_TIMEOUT}s waiting for $url"
}

wait_log() {
  local name="$1"
  local pattern="$2"
  local pid_file="$RUN_DIR/$name.pid"
  local log="$LOG_DIR/$name.log"
  local started="$SECONDS"
  local pid

  echo "Waiting for $name startup log ..."
  while (( SECONDS - started < STARTUP_TIMEOUT )); do
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if ! pid_is_running "$pid"; then
      local status=0
      wait "$pid" || status=$?
      fail_service "$name" "process exited before startup completed; status $status"
    fi

    if grep -E "$pattern" "$log" >/dev/null 2>&1; then
      echo "  $name ready"
      return 0
    fi
    sleep 2
  done

  fail_service "$name" "timed out after ${STARTUP_TIMEOUT}s waiting for log pattern: $pattern"
}

wait_temporal() {
  echo "Checking external Temporal at $TEMPORAL_ADDRESS ..."
  if ! temporal --disable-config-file --disable-config-env --address "$TEMPORAL_ADDRESS" \
    operator cluster health --command-timeout 5s >/dev/null 2>&1; then
    die "Temporal is not reachable at $TEMPORAL_ADDRESS. Start Temporal separately, then rerun ./scripts/local-up.sh.
Example:
  ./scripts/start-temporal-dev.sh --ip 127.0.0.1 --port 7233 --ui-ip 127.0.0.1 --ui-port 8233"
  fi
  echo "  Temporal ready"
}

run_temporal_setup() {
  local log="$LOG_DIR/temporal-setup.log"

  echo "Setting up Temporal namespaces and Nexus endpoints ..."
  if ! "$ROOT_DIR/scripts/setup-temporal-namespaces.sh" >"$log" 2>&1; then
    echo "ERROR: Temporal namespace setup failed. Log: $log" >&2
    tail -n 120 "$log" >&2 || true
    exit 1
  fi
}

check_artifacts() {
  local artifacts=(
    "$ROOT_DIR/java/apps/apps-api/target/apps-api-1.0.0-SNAPSHOT.jar"
    "$ROOT_DIR/java/apps/apps-workers/target/apps-workers-1.0.0-SNAPSHOT.jar"
    "$ROOT_DIR/java/processing/processing-api/target/processing-api-1.0.0-SNAPSHOT.jar"
    "$ROOT_DIR/java/processing/processing-workers/target/processing-workers-1.0.0-SNAPSHOT.jar"
    "$ROOT_DIR/java/fulfillment/fulfillment-api/target/fulfillment-api-1.0.0-SNAPSHOT.jar"
    "$ROOT_DIR/java/fulfillment/fulfillment-workers/target/fulfillment-workers-1.0.0-SNAPSHOT.jar"
    "$ROOT_DIR/java/enablements/enablements-api/target/enablements-api-1.0.0-SNAPSHOT.jar"
    "$ROOT_DIR/java/enablements/enablements-workers/target/enablements-workers-1.0.0-SNAPSHOT.jar"
  )
  local missing=0
  local artifact

  require_file "$ROOT_DIR/python/pyproject.toml" "The Python project is missing from this checkout."

  for artifact in "${artifacts[@]}"; do
    if [[ ! -f "$artifact" ]]; then
      missing=1
      break
    fi
  done

  if [[ "$missing" -eq 1 ]]; then
    require_command mvn
    echo "Java artifacts missing; building Java services ..."
    mvn -f "$ROOT_DIR/java/pom.xml" -DskipTests install
  fi

  for artifact in "${artifacts[@]}"; do
    require_file "$artifact" "Java build completed, but this artifact was not created."
  done
}

print_summary() {
  cat <<EOF

Local services are up.

Temporal:           $TEMPORAL_ADDRESS
Temporal UI:        http://$TEMPORAL_UI_HOST:$TEMPORAL_UI_PORT
apps-api:           http://localhost:8080
processing-api:     http://localhost:8070
fulfillment-api:    http://localhost:8060
enablements-api:    http://localhost:8050

Logs:               $LOG_DIR
PID files:          $RUN_DIR
Stop everything:    ./scripts/local-down.sh
EOF
}

main() {
  mkdir -p "$RUN_DIR" "$LOG_DIR"

  load_dotenv
  configure_local_environment

  require_command java
  require_command temporal
  require_command curl
  require_command uv

  check_artifacts
  ensure_no_existing_run
  check_required_ports

  wait_temporal
  run_temporal_setup

  start_service enablements-api java -jar "$ROOT_DIR/java/enablements/enablements-api/target/enablements-api-1.0.0-SNAPSHOT.jar" \
    --server.port=8050 \
    --management.server.port=9050
  wait_http enablements-api http://127.0.0.1:9050/actuator/health

  start_service fulfillment-api java -jar "$ROOT_DIR/java/fulfillment/fulfillment-api/target/fulfillment-api-1.0.0-SNAPSHOT.jar" \
    --server.port=8060 \
    --management.server.port=9071
  wait_http fulfillment-api http://127.0.0.1:9071/actuator/health

  start_service processing-api java -jar "$ROOT_DIR/java/processing/processing-api/target/processing-api-1.0.0-SNAPSHOT.jar" \
    --server.port=8070 \
    --management.server.port=9081
  wait_http processing-api http://127.0.0.1:9081/actuator/health

  start_service apps-api java -jar "$ROOT_DIR/java/apps/apps-api/target/apps-api-1.0.0-SNAPSHOT.jar" \
    --server.port=8080 \
    --management.server.port=9091
  wait_http apps-api http://127.0.0.1:9091/actuator/health

  start_service apps-workers env \
    TEMPORAL_DEPLOYMENT_NAME=apps \
    TEMPORAL_WORKER_BUILD_ID=local \
    java -jar "$ROOT_DIR/java/apps/apps-workers/target/apps-workers-1.0.0-SNAPSHOT.jar" \
    --server.port=8081 \
    --management.server.port=9092
  wait_http apps-workers http://127.0.0.1:9092/actuator/health

  start_service processing-workers env \
    TEMPORAL_DEPLOYMENT_NAME=processing \
    TEMPORAL_WORKER_BUILD_ID=local \
    java -jar "$ROOT_DIR/java/processing/processing-workers/target/processing-workers-1.0.0-SNAPSHOT.jar" \
    --server.port=8071 \
    --management.server.port=9082
  wait_http processing-workers http://127.0.0.1:9082/actuator/health

  start_service fulfillment-workers env \
    TEMPORAL_DEPLOYMENT_NAME=fulfillment \
    TEMPORAL_WORKER_BUILD_ID=local \
    java -jar "$ROOT_DIR/java/fulfillment/fulfillment-workers/target/fulfillment-workers-1.0.0-SNAPSHOT.jar" \
    --server.port=8061 \
    --management.server.port=9072
  wait_http fulfillment-workers http://127.0.0.1:9072/actuator/health

  start_service enablements-workers java -jar "$ROOT_DIR/java/enablements/enablements-workers/target/enablements-workers-1.0.0-SNAPSHOT.jar" \
    --server.port=-1 \
    --management.server.port=9052
  wait_log enablements-workers "Started .*WorkerApplication"

  start_service python-fulfillment-worker env \
    PYTHONPATH="$ROOT_DIR/python/generated:$ROOT_DIR/python/generated/pydantic:$ROOT_DIR/python/fulfillment${PYTHONPATH:+:$PYTHONPATH}" \
    TEMPORAL_FULFILLMENT_ADDRESS="$TEMPORAL_ADDRESS" \
    TEMPORAL_FULFILLMENT_NAMESPACE="$TEMPORAL_FULFILLMENT_NAMESPACE" \
    ENABLEMENTS_API_BASE_URL="$ENABLEMENTS_API_BASE_URL" \
    bash -lc 'cd python/fulfillment && exec uv run --project .. python -m src.worker'
  wait_log python-fulfillment-worker "All workers polling"

  UP_SUCCEEDED=1
  print_summary
}

main "$@"
