package datadog.smoketest.apmtracingdisabled

import okhttp3.Request

/**
 * {@code DD_TRACE_ENABLED=false} leaves no tracer to back LLM Observability spans, so the SDK must
 * stay no-op instead of throwing out of instrumented application code.
 */
class LlmObsTraceDisabledSmokeTest extends AbstractApmTracingDisabledSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    final String[] processProperties = [
      "-Ddd.trace.enabled=false",
      "-Ddd.llmobs.enabled=true",
      "-Ddd.llmobs.ml.app=apm-tracing-disabled-smoketest",
      "-Ddd.service.name=llmobs-trace-disabled-smoketest-app",
    ]
    return createProcess(processProperties)
  }

  @Override
  protected String traceAgentProtocolVersion() {
    return '0.4'
  }

  void 'the LLMObs SDK stays safe to call when tracing is disabled'() {
    setup:
    final url = "http://localhost:${httpPort}/rest-api/llmobs"
    final request = new Request.Builder().url(url).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
  }
}
