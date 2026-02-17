#!/bin/bash
#
# Run the DSM Kafka Config Capture E2E test.
#
# This script:
# 1. Builds the docker images
# 2. Starts kafka, zookeeper, mock-agent, and the test app
# 3. Waits for the app to finish
# 4. Checks the mock-agent logs for captured Kafka configs
# 5. Validates that:
#    - kafka_producer config was captured
#    - kafka_consumer config was captured
#    - Deduplication works (same config not sent twice)
#
# Prerequisites:
# - Docker and docker-compose installed
# - dd-java-agent jar built at ../dd-java-agent/build/libs/dd-java-agent-1.60.0-SNAPSHOT.jar
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== DSM Kafka Config Capture E2E Test ==="
echo ""

# Verify the tracer jar exists
TRACER_JAR="../dd-java-agent/build/libs/dd-java-agent-1.60.0-SNAPSHOT.jar"
if [ ! -f "$TRACER_JAR" ]; then
    echo "ERROR: Tracer jar not found at $TRACER_JAR"
    echo "Build it with: cd .. && ./gradlew :dd-java-agent:shadowJar"
    exit 1
fi
echo "[OK] Tracer jar found: $TRACER_JAR"

# Clean up previous run
echo ""
echo "--- Cleaning up previous containers ---"
docker compose down --remove-orphans 2>/dev/null || true

# Build and start
echo ""
echo "--- Building images ---"
docker compose build

echo ""
echo "--- Starting services ---"
docker compose up -d

echo ""
echo "--- Waiting for app to complete (up to 120s) ---"
# The app takes ~90s (10s wait + 30s DSM flush + 20s second flush + overhead)
TIMEOUT=120
START_TIME=$(date +%s)
while true; do
    ELAPSED=$(($(date +%s) - START_TIME))
    if [ $ELAPSED -ge $TIMEOUT ]; then
        echo "TIMEOUT: App did not complete within ${TIMEOUT}s"
        break
    fi

    # Check if the app container has exited
    APP_STATUS=$(docker compose ps --format json app 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('State','running'))" 2>/dev/null || echo "running")
    if [ "$APP_STATUS" = "exited" ]; then
        echo "App container exited after ${ELAPSED}s"
        break
    fi

    sleep 5
    echo "  ... waiting (${ELAPSED}s elapsed)"
done

# Collect logs
echo ""
echo "=== Mock Agent Logs ==="
AGENT_LOGS=$(docker compose logs mock-agent 2>/dev/null)
echo "$AGENT_LOGS"

echo ""
echo "=== App Logs (last 20 lines) ==="
docker compose logs app 2>/dev/null | tail -20

echo ""
echo "=== Verification ==="

# Check for kafka_producer config
PASS=true
if echo "$AGENT_LOGS" | grep -q "Type=kafka_producer"; then
    echo "[PASS] kafka_producer config was captured"
else
    echo "[FAIL] kafka_producer config was NOT found in agent logs"
    PASS=false
fi

# Check for kafka_consumer config
if echo "$AGENT_LOGS" | grep -q "Type=kafka_consumer"; then
    echo "[PASS] kafka_consumer config was captured"
else
    echo "[FAIL] kafka_consumer config was NOT found in agent logs"
    PASS=false
fi

# Check for bootstrap.servers config value
if echo "$AGENT_LOGS" | grep -q "bootstrap.servers = kafka:9092"; then
    echo "[PASS] bootstrap.servers config value is correct"
else
    echo "[FAIL] bootstrap.servers config value not found"
    PASS=false
fi

# Check for group.id config value (consumer-specific)
if echo "$AGENT_LOGS" | grep -q "group.id = dsm-test-consumer-group"; then
    echo "[PASS] group.id config value is correct"
else
    echo "[FAIL] group.id config value not found"
    PASS=false
fi

# Check for acks config value (producer-specific)
if echo "$AGENT_LOGS" | grep -q "acks = all"; then
    echo "[PASS] acks config value is correct"
else
    echo "[FAIL] acks config value not found"
    PASS=false
fi

# Check deduplication: kafka_producer should appear exactly once in CONFIG lines
PRODUCER_CONFIG_COUNT=$(echo "$AGENT_LOGS" | grep -c "CONFIG: Type=kafka_producer" || true)
if [ "$PRODUCER_CONFIG_COUNT" -eq 1 ]; then
    echo "[PASS] Deduplication works: kafka_producer config sent exactly once (count=$PRODUCER_CONFIG_COUNT)"
elif [ "$PRODUCER_CONFIG_COUNT" -gt 1 ]; then
    echo "[FAIL] Deduplication broken: kafka_producer config sent $PRODUCER_CONFIG_COUNT times (expected 1)"
    PASS=false
else
    echo "[FAIL] kafka_producer config count is 0 (expected 1)"
    PASS=false
fi

# Clean up
echo ""
echo "--- Cleaning up ---"
docker compose down --remove-orphans

echo ""
if [ "$PASS" = true ]; then
    echo "============================================"
    echo "  ALL CHECKS PASSED"
    echo "============================================"
    exit 0
else
    echo "============================================"
    echo "  SOME CHECKS FAILED"
    echo "============================================"
    exit 1
fi
