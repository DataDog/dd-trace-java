package datadog.smoketest.apmtracingdisabled

import datadog.trace.api.sampling.PrioritySampling
import okhttp3.Request

class LlmObsApmDisabledSmokeTest extends AbstractApmTracingDisabledSmokeTest {

  static final String LLMOBS_SERVICE_NAME = "llmobs-apm-disabled-test"

  static final String[] LLMOBS_APM_DISABLED_PROPERTIES = [
    "-Ddd.apm.tracing.enabled=false",
    "-Ddd.llmobs.enabled=true",
    "-Ddd.llmobs.ml-app=test-app",
    "-Ddd.service.name=${LLMOBS_SERVICE_NAME}",
  ]

  @Override
  ProcessBuilder createProcessBuilder() {
    return createProcess(LLMOBS_APM_DISABLED_PROPERTIES)
  }

  void 'When APM disabled and LLMObs enabled, LLMObs spans should be kept and APM spans should be dropped'() {
    setup:
    final llmobsUrl = "http://localhost:${httpPort}/rest-api/llmobs/test"
    final llmobsRequest = new Request.Builder().url(llmobsUrl).get().build()

    final apmUrl = "http://localhost:${httpPort}/rest-api/greetings"
    final apmRequest = new Request.Builder().url(apmUrl).get().build()

    when: "Create LLMObs span"
    final llmobsResponse = client.newCall(llmobsRequest).execute()

    then: "LLMObs request should succeed"
    llmobsResponse.successful

    when: "Create regular APM span"
    final apmResponse = client.newCall(apmRequest).execute()

    then: "APM request should succeed"
    apmResponse.successful

    and: "Wait for traces"
    waitForTraceCount(2)

    and: "LLMObs trace should be kept (SAMPLER_KEEP)"
    def llmobsTrace = traces.find { trace ->
      trace.spans.find { span ->
        span.meta["http.url"] == llmobsUrl
      }
    }
    assert llmobsTrace != null
    // The LLMObs child span should have LLMObs tags
    def llmobsChildSpan = llmobsTrace.spans.find { span ->
      span.meta["_ml_obs_tag.model_name"] == "gpt-4"
    }
    assert llmobsChildSpan != null : "LLMObs child span with model_name=gpt-4 should exist"

    and: "Regular APM trace should be dropped (SAMPLER_DROP)"
    def apmTrace = traces.find { trace ->
      trace.spans.find { span ->
        span.meta["http.url"] == apmUrl
      }
    }
    assert apmTrace != null
    checkRootSpanPrioritySampling(apmTrace, PrioritySampling.SAMPLER_DROP)

    and: "No NPE or errors in logs"
    !isLogPresent { it.contains("NullPointerException") }
    !isLogPresent { it.contains("ERROR") }
  }

  void 'standalone LLMObs span (no surrounding APM scope) should be kept and carry the LLMOBS trace-source bit'() {
    setup:
    final url = "http://localhost:${httpPort}/rest-api/llmobs/standalone"
    final req = new Request.Builder().url(url).get().build()

    when: "Trigger standalone LLMObs span (created on a fresh thread, no inherited APM scope)"
    final resp = client.newCall(req).execute()

    then:
    resp.successful

    and: "Wait for both traces (HTTP request trace + standalone LLMObs trace)"
    waitForTraceCount(2)

    and: "Standalone LLMObs trace should exist with the LLMOBS trace-source bit on the root and SAMPLER_KEEP"
    def llmobsTrace = traces.find { trace ->
      trace.spans.any { span -> span.meta["_ml_obs_tag.model_name"] == "gpt-4-standalone" }
    }
    assert llmobsTrace != null : "standalone LLMObs trace not found"
    // ProductTraceSource.LLMOBS = 0x20 → encoded as the hex string "20"
    final propagatedTraceSource = llmobsTrace.spans[0].meta["_dd.p.ts"]
    assert propagatedTraceSource?.contains("20") : "expected LLMOBS bit (0x20) in _dd.p.ts on root, got '${propagatedTraceSource}'"
    assert checkRootSpanPrioritySampling(llmobsTrace, PrioritySampling.SAMPLER_KEEP)

    and: "No NPE or errors in logs"
    !isLogPresent { it.contains("NullPointerException") }
    !isLogPresent { it.contains("ERROR") }
  }

  void 'LLMObs spans should have PROPAGATED_TRACE_SOURCE tag set'() {
    setup:
    final llmobsUrl = "http://localhost:${httpPort}/rest-api/llmobs/test"
    final llmobsRequest = new Request.Builder().url(llmobsUrl).get().build()

    when:
    final response = client.newCall(llmobsRequest).execute()

    then:
    response.successful
    waitForTraceCount(1)

    and: "LLMObs span should be created successfully"
    def trace = traces[0]
    assert trace != null
    def llmobsSpan = trace.spans.find { span ->
      span.meta["_ml_obs_tag.model_name"] == "gpt-4"
    }
    assert llmobsSpan != null : "LLMObs span with model_name should exist"
  }
}
