package datadog.smoketest.apmtracingdisabled

import okhttp3.Request

class LlmObsTraceDisabledSmokeTest extends AbstractApmTracingDisabledSmokeTest {

  static final String[] LLMOBS_TRACE_DISABLED_PROPERTIES = [
    "-Ddd.trace.enabled=false",
    "-Ddd.llmobs.enabled=true",
    "-Ddd.llmobs.ml-app=test-app",
    "-Ddd.service.name=llmobs-trace-disabled-test",
  ]

  @Override
  ProcessBuilder createProcessBuilder() {
    return createProcess(LLMOBS_TRACE_DISABLED_PROPERTIES)
  }

  void 'DD_TRACE_ENABLED=false with DD_LLMOBS_ENABLED=true should disable LLMObs gracefully'() {
    setup:
    final llmobsUrl = "http://localhost:${httpPort}/rest-api/llmobs/test"
    final llmobsRequest = new Request.Builder().url(llmobsUrl).get().build()

    when: "Call LLMObs endpoint"
    final response = client.newCall(llmobsRequest).execute()

    then: "Request should succeed"
    response.successful
    response.code() == 200

    and: "LLMObs disabled message in logs"
    isLogPresent { it.contains("LLM Observability is disabled: tracing is disabled") }
  }
}
