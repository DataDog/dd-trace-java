package datadog.smoketest.apmtracingdisabled

import datadog.trace.api.sampling.PrioritySampling
import okhttp3.Request
import spock.util.concurrent.PollingConditions

/**
 * With {@code DD_APM_TRACING_ENABLED=false}, LLM Observability keeps working — its spans go to the
 * LLM Observability intake, which is independent of APM sampling — while the APM traces they ride
 * on are dropped.
 */
class LlmObsApmTracingDisabledSmokeTest extends AbstractApmTracingDisabledSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder() {
    final String[] processProperties = [
      "-Ddd.apm.tracing.enabled=false",
      "-Ddd.llmobs.enabled=true",
      "-Ddd.llmobs.ml.app=apm-tracing-disabled-smoketest",
      "-Ddd.service.name=llmobs-apm-tracing-disabled-smoketest-app",
    ]
    return createProcess(processProperties)
  }

  @Override
  protected String traceAgentProtocolVersion() {
    return '0.4'
  }

  @Override
  Closure decodedEvpProxyMessageCallback() {
    return { String path, request ->
      // The payload is msgpack; the path alone tells us the LLM Observability intake was reached.
      return path.contains('api/v2/llmobs') ? path : null
    }
  }

  void 'LLMObs spans reach the LLM Observability intake while the APM trace is dropped'() {
    setup:
    final url = "http://localhost:${httpPort}/rest-api/llmobs"
    final request = new Request.Builder().url(url).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(1)

    and: 'the APM trace is dropped'
    checkRootSpanPrioritySampling(traces[0], PrioritySampling.SAMPLER_DROP)
    hasApmDisabledTagOnEverySpan(traces[0])

    and: 'the LLMObs span still reached the LLM Observability intake'
    new PollingConditions(timeout: 30).eventually {
      assert !evpProxyMessages.isEmpty()
    }
  }
}
