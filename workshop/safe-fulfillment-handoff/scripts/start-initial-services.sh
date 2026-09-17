#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

prepare_environment
require_command java
require_command curl

build_all_java

start_service apps-api java -jar "$ROOT_DIR/java/apps/apps-api/target/apps-api-1.0.0-SNAPSHOT.jar" \
  --server.port=8080 \
  --management.server.port=9091
wait_http apps-api http://127.0.0.1:9091/actuator/health

start_service apps-workers-v1 env \
  ACME_APPS_ORDER_WORKFLOW_CLASS=com.acme.apps.workflows.v2.OrderImpl \
  TEMPORAL_DEPLOYMENT_NAME=apps \
  TEMPORAL_WORKER_BUILD_ID=v2 \
  java -jar "$ROOT_DIR/java/apps/apps-workers/target/apps-workers-1.0.0-SNAPSHOT.jar" \
  --server.port=8081 \
  --management.server.port=9092
wait_http apps-workers-v1 http://127.0.0.1:9092/actuator/health

start_service processing-api java -jar "$ROOT_DIR/java/processing/processing-api/target/processing-api-1.0.0-SNAPSHOT.jar" \
  --server.port=8070 \
  --management.server.port=9081
wait_http processing-api http://127.0.0.1:9081/actuator/health

start_service processing-workers-v1 env \
  ACME_PROCESSING_ORDER_WORKFLOW_CLASS=com.acme.processing.workflows.v2.OrderImpl \
  TEMPORAL_DEPLOYMENT_NAME=processing \
  TEMPORAL_WORKER_BUILD_ID=v2 \
  java -jar "$ROOT_DIR/java/processing/processing-workers/target/processing-workers-1.0.0-SNAPSHOT.jar" \
  --server.port=8071 \
  --management.server.port=9082
wait_http processing-workers-v1 http://127.0.0.1:9082/actuator/health

start_service enablements-api java -jar "$ROOT_DIR/java/enablements/enablements-api/target/enablements-api-1.0.0-SNAPSHOT.jar" \
  --server.port=8050 \
  --management.server.port=9050
wait_http enablements-api http://127.0.0.1:9050/actuator/health

start_service enablements-workers java -jar "$ROOT_DIR/java/enablements/enablements-workers/target/enablements-workers-1.0.0-SNAPSHOT.jar" \
  --server.port=-1 \
  --management.server.port=9052
wait_log enablements-workers "Started .*WorkerApplication"

print_runtime_summary
