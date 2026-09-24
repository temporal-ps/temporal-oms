#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

prepare_environment
require_command java
require_command curl

build_apps_worker

start_service apps-workers-v2 env \
  ACME_APPS_ORDER_WORKFLOW_CLASS=com.acme.apps.workflows.v2.OrderImpl \
  TEMPORAL_DEPLOYMENT_NAME=apps \
  TEMPORAL_WORKER_BUILD_ID=v3 \
  java -jar "$ROOT_DIR/java/apps/apps-workers/target/apps-workers-1.0.0-SNAPSHOT.jar" \
  --server.port=8082 \
  --management.server.port=9093
wait_http apps-workers-v2 http://127.0.0.1:9093/actuator/health
