#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$SCRIPT_DIR/../web"

if [[ ! -d "$WEB_DIR/node_modules" ]]; then
  npm install --prefix "$WEB_DIR"
fi

exec npm run dev --prefix "$WEB_DIR"
