#!/usr/bin/env bash
set -euo pipefail

wls=/u01/oracle/wlserver/server/lib
ejb=/u01/oracle/oracle_common/modules/jakarta.ejb.jakarta.ejb-api.jar
security=/u01/oracle/properties/security.properties
export PROBE_USER="$(sed -n 's/^username=//p' "$security")"
export PROBE_PASS="$(sed -n 's/^password=//p' "$security")"
export DD_TRACE_WEBLOGIC_T3_ENABLED=true
export DD_WRITER_TYPE=PrintingWriter
export DD_REMOTE_CONFIG_ENABLED=false
export DD_INSTRUMENTATION_TELEMETRY_ENABLED=false
export DD_PROFILING_ENABLED=false
export DD_JMXFETCH_ENABLED=false
export DD_APPSEC_ENABLED=false
export DD_TRACE_STARTUP_LOGS=false

mkdir classes
javac -cp "agent.jar:$wls/wlthint3client.jar:$ejb" -d classes Caller.java Echo.java EchoBean.java Lookup.java
jar cf /tmp/echo.jar -C classes probe/Echo.class -C classes probe/EchoBean.class -C classes probe/Lookup.class

start_domain() {
  local service=$1 port=$2
  cp -R "$DOMAIN_HOME" "$service"
  (
    cd "$service"
    mkdir -p servers/admin-server/tmp
    cp "$security" servers/admin-server/security/boot.properties
    exec java -javaagent:/tmp/agent.jar -Ddd.service="$service" -Xms128m -Xmx350m \
      -Djava.io.tmpdir=/tmp -Dweblogic.Name=admin-server -Dweblogic.ListenAddress=127.0.0.1 \
      -Dweblogic.ListenPort="$port" -cp "$wls/weblogic.jar" weblogic.Server
  ) > "/tmp/$service.log" 2>&1 &
  local pid=$!

  for ((attempt = 0; attempt < 90; ++attempt)); do
    if grep -q 'Server state changed to RUNNING' "/tmp/$service.log"; then
      java -Xmx128m -cp "$wls/weblogic.jar" weblogic.Deployer -adminurl "t3://127.0.0.1:$port" \
        -username "$PROBE_USER" -password "$PROBE_PASS" -deploy -name t3probe \
        -source /tmp/echo.jar -targets admin-server
      return
    fi
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  echo "WebLogic failed to reach RUNNING for $service" >&2
  return 1
}

start_domain first 7001
start_domain second 7002
java -Xmx128m -Xverify:all -javaagent:/tmp/agent.jar -Ddd.service=caller \
  -cp "classes:$wls/wlthint3client.jar" probe.Caller
