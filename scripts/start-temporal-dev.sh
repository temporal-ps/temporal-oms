#!/usr/bin/env bash

set -Eeuo pipefail

# Standalone Nexus operations and standalone activities need dynamic-config
# flags that `temporal server start-dev` does not set by default. Any extra
# arguments passed to this script (e.g. --ip 0.0.0.0 --ui-ip 0.0.0.0) are
# forwarded to the dev server alongside them.
exec temporal server start-dev \
  --dynamic-config-value 'component.nexusoperations.callback.endpoint.template="http://localhost:7243/namespaces/{{.NamespaceName}}/nexus/callback"' \
  --dynamic-config-value 'callback.allowedAddresses=[{"Pattern":"*","AllowInsecure":true}]' \
  --dynamic-config-value 'history.enableUpdateCallbacks=true' \
  --dynamic-config-value 'nexusoperation.enableStandalone=true' \
  --dynamic-config-value 'activity.enableStandalone=true' \
  "$@"
