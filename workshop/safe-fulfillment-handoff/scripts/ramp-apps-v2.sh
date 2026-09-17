#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

prepare_environment
require_command temporal

PERCENTAGE="${1:-${RAMP_PERCENTAGE:-50}}"

temporal_cli worker deployment set-ramping-version \
  --deployment-name apps \
  --build-id v3 \
  --percentage "$PERCENTAGE" \
  --namespace "$TEMPORAL_APPS_NAMESPACE"

describe_deployment apps "$TEMPORAL_APPS_NAMESPACE"
