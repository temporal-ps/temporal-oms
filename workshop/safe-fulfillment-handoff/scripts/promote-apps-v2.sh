#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

prepare_environment
require_command temporal

set_current_version apps v3 "$TEMPORAL_APPS_NAMESPACE"
describe_deployment apps "$TEMPORAL_APPS_NAMESPACE"
