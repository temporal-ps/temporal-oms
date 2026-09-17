#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

prepare_environment
require_command java
require_command curl

build_processing_worker

start_service processing-workers-v2 env \
  ACME_PROCESSING_ORDER_WORKFLOW_CLASS=com.acme.processing.workflows.v2.OrderImpl \
  TEMPORAL_DEPLOYMENT_NAME=processing \
  TEMPORAL_WORKER_BUILD_ID=v3 \
  java -jar "$ROOT_DIR/java/processing/processing-workers/target/processing-workers-1.0.0-SNAPSHOT.jar" \
  --server.port=8072 \
  --management.server.port=9083
wait_http processing-workers-v2 http://127.0.0.1:9083/actuator/health
