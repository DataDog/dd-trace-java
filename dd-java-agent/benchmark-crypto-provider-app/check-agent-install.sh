#!/usr/bin/env bash
# Starts the demo app with the agent under each flag scenario and checks the installer outcome.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/bench-env.sh"
AGENT_JAR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -a) AGENT_JAR="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$AGENT_JAR" && -f "$AGENT_JAR" ]] || { echo "-a <dd-java-agent.jar> is required" >&2; exit 2; }
APP_LIB="$HERE/build/app/lib"
TOOLS_JAR="$HERE/build/libs/crypto-bench-tools.jar"
require_built "$APP_LIB/crypto-bench-app.jar" "$TOOLS_JAR" "$ACCP_JAR"
PORT="${APP_PORT:-18080}"
LOGS="$(mktemp -d)"
echo "security.provider.13=SunPKCS11" > "$LOGS/custom.security"

FAILED=0
# SCENARIO_ENV=K=V (optional) | name | expected position1 | expected installer log fragment ("-" = no installer line) | extra args...
scenario() {
  local name="$1" expect_pos1="$2" expect_log="$3"; shift 3
  local log="$LOGS/$name.log"
  # shellcheck disable=SC2086
  env ${SCENARIO_ENV:-} "$JAVA" "-javaagent:$AGENT_JAR" -Ddd.trace.debug=true -Ddd.trace.agent.url=http://127.0.0.1:1 \
    -Ddd.remote_config.enabled=false -Ddd.telemetry.enabled=false -Ddd.jmxfetch.enabled=false \
    -Ddd.trace.experimental.crypto.provider.start-delay=0 \
    "-DPORT=$PORT" -DHTTPS_PORT=$((PORT + 1)) "$@" com.example.cryptobench.CryptoBenchApplication > "$log" 2>&1 &
  local pid=$!
  "$JAVA" -cp "$TOOLS_JAR" com.example.cryptobench.tools.LoadGen wait --base "http://127.0.0.1:$PORT" --timeout 120
  sleep 1 # the installer runs asynchronously; give it time to log
  local pos1
  pos1="$("$JAVA" -cp "$TOOLS_JAR" com.example.cryptobench.tools.LoadGen get --url "http://127.0.0.1:$PORT/admin/providers" | sed -n 's/^position1=//p')"
  kill "$pid"; wait "$pid" 2>/dev/null || true
  local line
  line="$(grep -o 'Experimental crypto provider: .*' "$log" | head -1 || true)"
  local ok=yes
  [[ "$pos1" == "$expect_pos1" ]] || ok=no
  if [[ "$expect_log" == "-" ]]; then [[ -z "$line" ]] || ok=no; else [[ "$line" == *"$expect_log"* ]] || ok=no; fi
  printf '%-22s %-4s position1=%-30s %s\n' "$name" "$ok" "$pos1" "${line:-<no installer log>}"
  [[ "$ok" == yes ]] || FAILED=1
}

scenario flag-off SUN - -cp "$APP_LIB/*:$ACCP_JAR" -Ddd.trace.experimental.crypto.provider.enabled=false
scenario flag-default SUN - -cp "$APP_LIB/*:$ACCP_JAR"
scenario flag-on AmazonCorrettoCryptoProvider "installed AmazonCorrettoCryptoProvider" \
  -cp "$APP_LIB/*:$ACCP_JAR" -Ddd.trace.experimental.crypto.provider.enabled=true
SCENARIO_ENV=DD_TRACE_EXPERIMENTAL_CRYPTO_PROVIDER_ENABLED=true \
  scenario flag-on-env-var AmazonCorrettoCryptoProvider "installed AmazonCorrettoCryptoProvider" \
  -cp "$APP_LIB/*:$ACCP_JAR"
scenario no-accp-jar SUN "NOT_ON_CLASSPATH" -cp "$APP_LIB/*" -Ddd.trace.experimental.crypto.provider.enabled=true
scenario custom-security-props SUN "SKIPPED_CUSTOM_SECURITY_PROPERTIES" -cp "$APP_LIB/*:$ACCP_JAR" \
  "-Djava.security.properties=$LOGS/custom.security" -Ddd.trace.experimental.crypto.provider.enabled=true
scenario bc-fips-approved-only SUN "SKIPPED_FIPS" -cp "$APP_LIB/*:$ACCP_JAR" \
  -Dorg.bouncycastle.fips.approved_only=true -Ddd.trace.experimental.crypto.provider.enabled=true
rm -rf "$LOGS"
exit "$FAILED"
