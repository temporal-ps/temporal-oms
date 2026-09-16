#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUN_DIR="$ROOT_DIR/.workshop/run"
QUIET=0

if [[ "${1:-}" == "--quiet" ]]; then
  QUIET=1
fi

service_names=(
  python-fulfillment-worker
  enablements-workers
  fulfillment-workers
  processing-workers
  apps-workers
  apps-api
  processing-api
  fulfillment-api
  enablements-api
)

say() {
  if [[ "$QUIET" -eq 0 ]]; then
    echo "$@"
  fi
}

pid_is_running() {
  local pid="$1"
  [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1
}

send_stop_signal() {
  local name="$1"
  local pid_file="$RUN_DIR/$name.pid"
  local pid

  if [[ ! -f "$pid_file" ]]; then
    return 0
  fi

  pid="$(cat "$pid_file" 2>/dev/null || true)"
  if ! pid_is_running "$pid"; then
    say "$name is not running"
    rm -f "$pid_file"
    return 0
  fi

  say "Stopping $name (pid $pid) ..."
  kill "$pid" >/dev/null 2>&1 || true
}

wait_for_stop() {
  local name="$1"
  local pid_file="$RUN_DIR/$name.pid"
  local pid

  if [[ ! -f "$pid_file" ]]; then
    return 0
  fi

  pid="$(cat "$pid_file" 2>/dev/null || true)"

  local waited=0
  while pid_is_running "$pid" && [[ "$waited" -lt 20 ]]; do
    sleep 1
    waited=$((waited + 1))
  done

  if pid_is_running "$pid"; then
    say "  $name did not stop after 20s; sending SIGKILL"
    kill -9 "$pid" >/dev/null 2>&1 || true
  fi

  rm -f "$pid_file"
}

main() {
  if [[ ! -d "$RUN_DIR" ]]; then
    say "No local services are running."
    return 0
  fi

  local found=0
  local name
  for name in "${service_names[@]}"; do
    if [[ -f "$RUN_DIR/$name.pid" ]]; then
      found=1
    fi
    send_stop_signal "$name"
  done

  for name in "${service_names[@]}"; do
    wait_for_stop "$name"
  done

  if [[ "$found" -eq 0 ]]; then
    say "No local services are running."
  else
    say "Local services stopped."
  fi
}

main "$@"
