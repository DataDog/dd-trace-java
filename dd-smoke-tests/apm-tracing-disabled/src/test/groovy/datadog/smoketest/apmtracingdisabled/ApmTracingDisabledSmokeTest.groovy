package datadog.smoketest.apmtracingdisabled

import okhttp3.Request

class ApmTracingDisabledSmokeTest extends AbstractApmTracingDisabledSmokeTest {

  private static final String APM_TRACING_DISABLED_SERVICE_NAME = "asm-standalone-billing-smoketest-app"
  private static final String APM_ENABLED_SERVICE_NAME = "apm-enabled-smoketest-app"

  static final String[] APM_TRACING_DISABLED_PROPERTIES = [
    "-Ddd.apm.tracing.enabled=false",
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    "-Ddd.appsec.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${APM_TRACING_DISABLED_SERVICE_NAME}",
  ]

  static final String[] APM_ENABLED_PROPERTIES = ["-Ddd.service.name=${APM_ENABLED_SERVICE_NAME}", "-Ddd.trace.tracer.metrics.enabled=true",]

  protected int numberOfProcesses() {
    return 2
  }

  @Override
  ProcessBuilder createProcessBuilder(int processIndex) {
    if(processIndex == 0){
      return createProcess(processIndex, APM_TRACING_DISABLED_PROPERTIES)
    }
    return createProcess(processIndex, APM_ENABLED_PROPERTIES)
  }

  void 'When APM is disabled, numeric tag _dd.apm.enabled:0 must be added to the metrics map of the service entry spans.'() {
    setup:
    final url1 = "http://localhost:${httpPorts[0]}/rest-api/greetings"
    final request1 = new Request.Builder().url(url1).get().build()
    final url2 = "http://localhost:${httpPorts[1]}/rest-api/greetings"
    final request2 = new Request.Builder().url(url2).get().build()

    when:
    final response1 = client.newCall(request1).execute()
    final response2 = client.newCall(request2).execute()

    then:
    response1.successful
    response2.successful
    waitForTraceCount(2)
    hasApmDisabledTag(getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME))
    !hasApmDisabledTag(getServiceTrace(APM_ENABLED_SERVICE_NAME))
  }

  void 'When APM is disabled, libraries must completely disable the generation of APM trace metrics'(){
    setup:
    final url1 = "http://localhost:${httpPorts[0]}/rest-api/greetings"
    final request1 = new Request.Builder().url(url1).get().build()

    when:
    client.newCall(request1).execute()

    then: 'Check if Datadog-Client-Computed-Stats header is present and set to true, Disabling the metrics computation agent-side'
    waitForTraceCount(1)
    lastTraceRequestHeaders != null
    def computedStatsHeader = lastTraceRequestHeaders.get('Datadog-Client-Computed-Stats')
    assert computedStatsHeader != null && computedStatsHeader == 'true'

    then: 'metrics should be disabled'
    isLogPresent { it.contains('datadog.trace.agent.common.metrics.MetricsAggregatorFactory - tracer metrics disabled') }
  }

  void 'test _dd.p.ts propagation for appsec event'() {
    setup:
    final downstreamUrl = "http://localhost:${httpPorts[1]}/rest-api/greetings"
    final url = localUrl + "url=${downstreamUrl}"
    final request = new Request.Builder().url(url).get().build()

    when: "Request to an endpoint that triggers ASM events and then calls another endpoint"
    final response1 = client.newCall(request).execute()

    then: "Both traces should have a root span with _dd.p.ts=02 tag"
    response1.successful
    waitForTraceCount(2)
    assert traces.size() == 2
    hasAppsecPropagationTag(traces.get(0))
    hasAppsecPropagationTag(traces.get(1))

    where:
    localUrl << [
      "http://localhost:${httpPorts[0]}/rest-api/appsec/appscan_fingerprint?",
      "http://localhost:${httpPorts[0]}/rest-api/iast?injection=vulnerable&"
    ]
  }
}
