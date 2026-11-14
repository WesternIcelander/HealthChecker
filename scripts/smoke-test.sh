#!/usr/bin/env bash
# Smoke test of the packaged app.
#
# Builds the runnable app jar (mvn package) and the MinecraftChecker plugin jar, derives a
# test config from config.example.json, starts the jar with the plugin in plugins/, checks
# /health, /api/status and /, then stops it with SIGTERM and checks it shut down cleanly.
#
# Nothing outside this machine is contacted: every endpoint target, notification channel
# and login provider in the derived config points at loopback. The "website" endpoint
# checks HealthChecker's own dashboard, the "survival" endpoint a fake Minecraft status
# server from the plugin's test classes, and the others a closed port (they go DOWN).
#
# Usage: scripts/smoke-test.sh
# Environment:
#   PLUGIN_DIR      the MinecraftChecker repo (default: plugin-projects/MinecraftChecker)
#   SMOKE_TIMEOUT   seconds to wait for the endpoints to settle (default: 30)
#   SMOKE_KEEP=1    keep the temporary directory for inspection
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PLUGIN_DIR="${PLUGIN_DIR:-$ROOT/plugin-projects/MinecraftChecker}"
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-30}"
CLOSED="127.0.0.1:1"

log() { printf '[smoke] %s\n' "$*" >&2; }
fail() { log "FAIL: $*"; exit 1; }

for tool in java mvn curl jq; do
    command -v "$tool" >/dev/null || fail "$tool not found on PATH"
done
[[ -f "$PLUGIN_DIR/pom.xml" ]] || fail "MinecraftChecker not found at $PLUGIN_DIR (set PLUGIN_DIR)"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/hc-smoke.XXXXXX")"
FAKE_PID=""
HC_PID=""
cleanup() {
    local status=$?
    set +e
    [[ -n "$HC_PID" ]] && kill "$HC_PID" 2>/dev/null && wait "$HC_PID" 2>/dev/null
    [[ -n "$FAKE_PID" ]] && kill "$FAKE_PID" 2>/dev/null && wait "$FAKE_PID" 2>/dev/null
    if [[ $status -ne 0 ]]; then
        for f in "$WORK"/*.log; do
            [[ -f "$f" ]] || continue
            log "--- $(basename "$f") ---"
            cat "$f" >&2
        done
    fi
    if [[ "${SMOKE_KEEP:-}" == 1 ]]; then
        log "kept $WORK"
    else
        rm -rf "$WORK"
    fi
    exit $status
}
trap cleanup EXIT
trap 'exit 130' INT TERM

log "building HealthChecker (mvn install, incl. tests)"
(cd "$ROOT" && mvn -q install)
APP_JAR="$ROOT/app/target/HealthChecker-1.0-SNAPSHOT.jar"
[[ -f "$APP_JAR" ]] || fail "app jar not found: $APP_JAR"

log "building MinecraftChecker (mvn package, incl. tests)"
(cd "$PLUGIN_DIR" && mvn -q package)
PLUGIN_JAR="$PLUGIN_DIR/target/MinecraftChecker-1.0-SNAPSHOT.jar"
[[ -f "$PLUGIN_JAR" ]] || fail "plugin jar not found: $PLUGIN_JAR"

mkdir -p "$WORK/plugins"
cp "$PLUGIN_JAR" "$WORK/plugins/"

log "starting the fake Minecraft status server"
java -cp "$PLUGIN_DIR/target/classes:$PLUGIN_DIR/target/test-classes" \
    io.siggi.minecraftchecker.FakeStatusServerMain >"$WORK/fake.out" 2>"$WORK/fake.log" &
FAKE_PID=$!
for _ in $(seq 1 100); do
    grep -q '^freePort=' "$WORK/fake.out" 2>/dev/null && break
    kill -0 "$FAKE_PID" 2>/dev/null || fail "fake status server exited"
    sleep 0.1
done
MC_PORT="$(sed -n 's/^minecraftPort=//p' "$WORK/fake.out")"
PORT="$(sed -n 's/^freePort=//p' "$WORK/fake.out")"
[[ -n "$MC_PORT" && -n "$PORT" ]] || fail "fake status server didn't report its ports"
log "fake status server on 127.0.0.1:$MC_PORT; HealthChecker will use port $PORT"

# The admin's password hash, made with the app's own hasher (hash-password needs a console).
cat >"$WORK/Hash.java" <<'EOF'
public class Hash {
    public static void main(String[] args) {
        System.out.println(new io.siggi.healthchecker.auth.PasswordHasher().hash(args[0].toCharArray()));
    }
}
EOF
ADMIN_PASSWORD_HASH="$(java -cp "$APP_JAR" "$WORK/Hash.java" smoke-test-password)"
export ADMIN_PASSWORD_HASH
for var in GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GITHUB_CLIENT_ID GITHUB_CLIENT_SECRET \
           SIMPLEMAILER_KEY; do
    export "$var=smoke-$var"
done
export DISCORD_WEBHOOK="http://$CLOSED/api/webhooks/1/smoke"
export MC_DISCORD_WEBHOOK="http://$CLOSED/api/webhooks/2/smoke"

log "deriving config.json from config.example.json"
jq --argjson port "$PORT" --argjson mcPort "$MC_PORT" --arg closed "$CLOSED" '
    .server.port = $port
    | .server.bind = "127.0.0.1"
    | .server.publicBaseUrl = "http://localhost:\($port)"
    | .sites |= map(.publicBaseUrl = "http://localhost:\($port)")
    | .auth.providers |= map(
        if .type == "oidc" then .issuer = "http://\($closed)"
        else .authorizeUrl = "http://\($closed)/authorize"
           | .tokenUrl = "http://\($closed)/token"
           | .userinfoUrl = "http://\($closed)/user" end)
    | .notifications.channels |= with_entries(
        if .value.type == "email" then .value.baseUrl = "http://\($closed)" else . end)
    | .endpoints |= map(
        .intervalSeconds = 2 | .timeoutMillis = 2000
        | if .id == "website" then .url = "http://127.0.0.1:\($port)"
          elif .id == "survival" then .host = "127.0.0.1" | .port = $mcPort
          elif .type == "http" then .url = "http://\($closed)"
          else .host = "127.0.0.1" | .port = 1 end)
' "$ROOT/config.example.json" >"$WORK/config.json"

# Every external host from the example must be gone, so no check, notification or login
# reaches outside this machine.
if grep -Eo '(https?://|"host": *")[^"/]+' "$WORK/config.json" | grep -Ev '127\.0\.0\.1|localhost' \
        | grep -Ev '^https?://(status|mc-status|web-status)\.example\.com$'; then
    fail "derived config still points somewhere other than loopback"
fi

log "starting HealthChecker"
(cd "$WORK" && exec java -jar "$APP_JAR" run config.json) >"$WORK/healthchecker.log" 2>&1 &
HC_PID=$!

BASE="http://127.0.0.1:$PORT"
status=""
deadline=$((SECONDS + SMOKE_TIMEOUT))
while ((SECONDS < deadline)); do
    kill -0 "$HC_PID" 2>/dev/null || fail "HealthChecker exited"
    status="$(curl -sf --max-time 5 "$BASE/api/status" || true)"
    if [[ -n "$status" ]] && jq -e '
            [.. | objects | select(.id? == "website" or .id? == "survival") | .state] as $s
            | ($s | length) == 2 and all($s[]; . == "UP")' <<<"$status" >/dev/null 2>&1; then
        break
    fi
    status=""
    sleep 0.5
done
[[ -n "$status" ]] || fail "website and survival not UP via $BASE/api/status within ${SMOKE_TIMEOUT}s"
grep -q 'Loaded plugin.*minecraft' "$WORK/healthchecker.log" || fail "plugin load not logged"

log "GET /health"
health_code="$(curl -s -o "$WORK/health.body" -w '%{http_code}' --max-time 5 "$BASE/health")"
[[ "$health_code" == 200 ]] || fail "/health returned $health_code: $(cat "$WORK/health.body")"
cat "$WORK/health.body" >&2; echo >&2

log "GET /api/status (anonymous)"
jq -r '"overall: \(.overall), viewer: \(.viewer)",
        (.categories[] | .endpoints[] | "  \(.id): \(.state)")' <<<"$status" >&2

log "GET /"
page_code="$(curl -s -o "$WORK/index.html" -w '%{http_code}' --max-time 5 "$BASE/")"
[[ "$page_code" == 200 ]] || fail "/ returned $page_code"
grep -q 'Website' "$WORK/index.html" || fail "/ doesn't list the public Website endpoint"
grep -q 'Survival' "$WORK/index.html" || fail "/ doesn't list the public-status Survival endpoint"
for text in "$(cat "$WORK/index.html")" "$status"; do
    if grep -q 'Admin panel\|Test server' <<<"$text"; then
        fail "private endpoints are visible to an anonymous visitor"
    fi
    if grep -Fq -e "127.0.0.1" -e "$MC_PORT" -e "\"port\"" <<<"$text"; then
        fail "an endpoint target is visible to an anonymous visitor"
    fi
done

log "stopping HealthChecker (SIGTERM)"
kill -TERM "$HC_PID"
exit_code=0
for _ in $(seq 1 300); do
    kill -0 "$HC_PID" 2>/dev/null || break
    sleep 0.1
done
kill -0 "$HC_PID" 2>/dev/null && fail "HealthChecker didn't stop within 30s of SIGTERM"
wait "$HC_PID" || exit_code=$?
HC_PID=""
# 143 = 128 + SIGTERM: the JVM ran its shutdown hooks and exited because of the signal.
[[ "$exit_code" == 0 || "$exit_code" == 143 ]] || fail "HealthChecker exited with $exit_code"
if grep -E 'SEVERE|Shutdown: closing the .* failed|Exception' "$WORK/healthchecker.log"; then
    fail "errors in the log"
fi
ls "$WORK/data" >/dev/null 2>&1 || fail "no data/ directory was written"
log "PASS (exit code $exit_code)"
