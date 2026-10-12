#!/usr/bin/env bash
# Helpers for scripts/kind/demo-up.sh and scripts/k3d/demo-up.sh with OVERLAY=local:
# a host Temporal dev server reachable from cluster pods, and a background tunnel.
# Callers set PROJECT_DIR before sourcing. PID files and logs share local-up.sh's
# .workshop/ directories under distinct names.

DEMO_RUN_DIR="$PROJECT_DIR/.workshop/run"
DEMO_LOG_DIR="$PROJECT_DIR/.workshop/logs"
DEMO_TEMPORAL_ADDRESS="127.0.0.1:7233"

demo_pid_running() {
    local pid_file="$1"
    local pid
    [ -f "$pid_file" ] || return 1
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    [ -n "$pid" ] && kill -0 "$pid" >/dev/null 2>&1
}

demo_temporal_healthy() {
    temporal --disable-config-file --disable-config-env --address "$DEMO_TEMPORAL_ADDRESS" \
        operator cluster health --command-timeout 5s >/dev/null 2>&1
}

# Fails when local-up.sh's process stack is running: it holds the tunnel's ports, and its
# dev server listens on 127.0.0.1 only, which cluster pods cannot reach.
demo_require_no_local_stack() {
    local pid_file
    for pid_file in "$DEMO_RUN_DIR"/*.pid; do
        [ -e "$pid_file" ] || continue
        case "$(basename "$pid_file")" in
            demo-*) continue ;;
        esac
        if demo_pid_running "$pid_file"; then
            echo "ERROR: the local-process stack is running ($(basename "$pid_file" .pid))." >&2
            echo "       Stop it first: ./scripts/local-down.sh" >&2
            exit 1
        fi
    done
}

# Reuses a healthy dev server; otherwise starts one bound to 0.0.0.0 so pods reach it
# through host.docker.internal. demo_stop_host_temporal stops only a server started here.
demo_ensure_host_temporal() {
    mkdir -p "$DEMO_RUN_DIR" "$DEMO_LOG_DIR"
    if demo_temporal_healthy; then
        echo "→ Reusing the Temporal server at $DEMO_TEMPORAL_ADDRESS (it must listen on 0.0.0.0 for pods to reach it)"
        return 0
    fi

    echo "→ Starting the Temporal dev server on 0.0.0.0:7233 (UI on :8233) ..."
    "$PROJECT_DIR/scripts/start-temporal-dev.sh" --ip 0.0.0.0 --ui-ip 0.0.0.0 \
        >"$DEMO_LOG_DIR/demo-temporal-dev.log" 2>&1 &
    echo $! >"$DEMO_RUN_DIR/demo-temporal-dev.pid"

    for _ in $(seq 60); do
        if demo_temporal_healthy; then
            echo "  Temporal ready (log: $DEMO_LOG_DIR/demo-temporal-dev.log)"
            return 0
        fi
        if ! demo_pid_running "$DEMO_RUN_DIR/demo-temporal-dev.pid"; then
            break
        fi
        sleep 1
    done
    echo "ERROR: the Temporal dev server did not become healthy. Last log lines:" >&2
    tail -n 40 "$DEMO_LOG_DIR/demo-temporal-dev.log" >&2 || true
    exit 1
}

# Namespaces, Nexus endpoints, and search attributes. Worker Deployment versions are left
# unregistered by default (SKIP_VERSION_REGISTRATION=1) so the first Admin rollout starts
# from a true OMS v1.
demo_setup_namespaces() {
    echo "→ Setting up Temporal namespaces and Nexus endpoints ..."
    if ! "$PROJECT_DIR/scripts/setup-temporal-namespaces.sh" >"$DEMO_LOG_DIR/demo-temporal-setup.log" 2>&1; then
        echo "ERROR: Temporal namespace setup failed. Log: $DEMO_LOG_DIR/demo-temporal-setup.log" >&2
        tail -n 60 "$DEMO_LOG_DIR/demo-temporal-setup.log" >&2 || true
        exit 1
    fi
}

# Starts the runner's tunnel.sh in the background and waits for the web UI and
# enablements-api to answer through it.
demo_start_tunnel() {
    local tunnel_script="$1"
    mkdir -p "$DEMO_RUN_DIR" "$DEMO_LOG_DIR"
    if demo_pid_running "$DEMO_RUN_DIR/demo-tunnel.pid"; then
        echo "→ Tunnel already running"
        return 0
    fi

    echo "→ Starting port-forwards in the background ..."
    bash "$tunnel_script" >"$DEMO_LOG_DIR/demo-tunnel.log" 2>&1 &
    echo $! >"$DEMO_RUN_DIR/demo-tunnel.pid"

    for _ in $(seq 30); do
        if curl -fsS --max-time 2 http://localhost:8050/actuator/health >/dev/null 2>&1 \
            && curl -fsS --max-time 2 -o /dev/null http://localhost:3000/; then
            echo "  Tunnel ready (log: $DEMO_LOG_DIR/demo-tunnel.log)"
            return 0
        fi
        sleep 2
    done
    echo "WARNING: the tunnel is running but localhost:3000 or :8050 is not answering yet." >&2
    echo "         Check $DEMO_LOG_DIR/demo-tunnel.log" >&2
}

demo_stop_pid() {
    local name="$1"
    local pid_file="$DEMO_RUN_DIR/$name.pid"
    local pid
    [ -f "$pid_file" ] || return 0
    pid="$(cat "$pid_file" 2>/dev/null || true)"
    if demo_pid_running "$pid_file"; then
        echo "→ Stopping $name (pid $pid) ..."
        kill "$pid" >/dev/null 2>&1 || true
        for _ in $(seq 20); do
            kill -0 "$pid" >/dev/null 2>&1 || break
            sleep 1
        done
        if kill -0 "$pid" >/dev/null 2>&1; then
            kill -9 "$pid" >/dev/null 2>&1 || true
        fi
    fi
    rm -f "$pid_file"
}

demo_stop_tunnel() {
    demo_stop_pid demo-tunnel
}

demo_stop_host_temporal() {
    demo_stop_pid demo-temporal-dev
}
