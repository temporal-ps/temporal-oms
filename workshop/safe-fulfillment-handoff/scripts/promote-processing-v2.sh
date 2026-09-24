#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

prepare_environment
require_command temporal

set_current_version processing v3 "$TEMPORAL_PROCESSING_NAMESPACE"
describe_deployment processing "$TEMPORAL_PROCESSING_NAMESPACE"
