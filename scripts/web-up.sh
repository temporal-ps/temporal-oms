#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$SCRIPT_DIR/../web"

if [[ ! -d "$WEB_DIR/node_modules" ]]; then
  npm install --prefix "$WEB_DIR"
fi

# Run vite directly, not through `npm run dev`, so this script's PID is the dev server
# itself and local-down.sh can stop it with one signal.
cd "$WEB_DIR"
exec ./node_modules/.bin/vite dev "$@"
