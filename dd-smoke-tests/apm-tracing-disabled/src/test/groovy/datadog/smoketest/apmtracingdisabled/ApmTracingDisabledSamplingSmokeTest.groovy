package datadog.smoketest.apmtracingdisabled

import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.test.util.Flaky
import groovy.json.JsonSlurper
import okhttp3.Request

@Flaky
class ApmTracingDisabledSamplingSmokeTest extends AbstractApmTracingDisabledSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder(){
    final String[] processProperties = [
      "-Ddd.apm.tracing.enabled=false",
      "-Ddd.iast.enabled=true",
      "-Ddd.appsec.enabled=true",
      "-Ddd.iast.detection.mode=FULL",
      "-Ddd.iast.debug.enabled=true",
      "-Ddd.service.name=apm-tracing-disabled-sampling-spring-smoketest-app",
    ]
    return createProcess(processProperties)
  }

  void 'test force keep call using time sampling'() {
    setup:
    final vulnerableUrl = "http://localhost:${httpPorts[0]}/rest-api/iast?injection=xss"
    final vulnerableRequest = new Request.Builder().url(vulnerableUrl).get().build()
    final forceKeepUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings?forceKeep=true"
    final forceKeepRequest = new Request.Builder().url(forceKeepUrl).get().build()
    final noForceKeepUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings"
    final noForceKeepRequest = new Request.Builder().url(noForceKeepUrl).get().build()

    when: "firs request with ASM events"
    final vulnerableResponse = client.newCall(vulnerableRequest).execute()

    then: "First trace should have a root span with USER_KEEP sampling priority due to ASM events"
    vulnerableResponse.successful
    waitForTraceCount(1)
    assert traces.size() == 1
    checkRootSpanPrioritySampling(traces[0], PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag(traces[0])

    when: "Request without ASM events and  no force kept span"
    final noForceKeepResponse = client.newCall(noForceKeepRequest).execute()

    then: "This trace should enter into the sampling mechanism and have a root span with SAMPLER_KEEP sampling priority as it's the first span checked in a minute"
    noForceKeepResponse.successful
    waitForTraceCount(2)
    assert traces.size() == 2
    checkRootSpanPrioritySampling(traces[1], PrioritySampling.SAMPLER_KEEP)
    !hasAppsecPropagationTag(traces[1])

    when: "Request without ASM events and force kept span"
    final forceKeepResponse = client.newCall(forceKeepRequest).execute()

    then: "This trace should have a root span with SAMPLER_DROP sampling priority although it's force kept, as it's not the first span checked in a minute and it has not ASM events"
    forceKeepResponse.successful
    waitForTraceCount(3)
    assert traces.size() == 3
    checkRootSpanPrioritySampling(traces[2], PrioritySampling.SAMPLER_DROP)
    !hasAppsecPropagationTag(traces[2])

    when: "Second request without ASM events and no force kept span"
    final noForceKeepResponse2 = client.newCall(noForceKeepRequest).execute()

    then: "This trace should enter into the sampling mechanism and have a root span with SAMPLER_DROP sampling priority as it's not the first span checked in a minute"
    noForceKeepResponse2.successful
    waitForTraceCount(4)
    assert traces.size() == 4
    checkRootSpanPrioritySampling(traces[3], PrioritySampling.SAMPLER_DROP)
    !hasAppsecPropagationTag(traces[3])
  }

  void 'test propagation in single process'(){
    setup:
    final downstreamUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings"
    final standAloneBillingUrl = "http://localhost:${httpPorts[0]}/rest-api/iast?injection=vulnerable%26url=${downstreamUrl}"
    final upstreamUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings?&url=${standAloneBillingUrl}"
    final request = new Request.Builder().url(upstreamUrl).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(3)

    and: 'No upstream ASM events'
    def upstreamTrace = getServiceTraceFromUrl(upstreamUrl)
    isSampledBySampler (upstreamTrace)
    !hasAppsecPropagationTag (upstreamTrace)
    hasApmDisabledTag (upstreamTrace)
    def upstreamTraceId = upstreamTrace.spans[0].traceId

    and: 'ASM events, resulting in force keep and appsec propagation'
    def standAloneBillingTrace = getServiceTraceFromUrl("http://localhost:${httpPorts[0]}/rest-api/iast?injection=vulnerable&url=http://localhost:${httpPorts[0]}/rest-api/greetings")
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)
    def standAloneBillingTraceId = standAloneBillingTrace.spans[0].traceId
    upstreamTraceId != standAloneBillingTraceId //There is no propagation!!!!

    and: 'Default APM distributed tracing behavior with'
    def downstreamTrace = getServiceTraceFromUrl(downstreamUrl)
    checkRootSpanPrioritySampling(downstreamTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (downstreamTrace)
    hasApmDisabledTag (downstreamTrace)
    def downstreamTraceId = downstreamTrace.spans[0].traceId
    standAloneBillingTraceId == downstreamTraceId //There is propagation
  }

  void 'test propagation simulating 3 process'(){
    setup:
    def jsonSlurper = new JsonSlurper()
    final traceId = "1212121212121212121"
    final parentId = "34343434"
    final url = "http://localhost:${httpPorts[0]}/rest-api/appsec/appscan_fingerprint?url=http://localhost:${httpPorts[0]}/rest-api/returnheaders"
    final request = new Request.Builder()
      .url(url)
      .header("x-datadog-trace-id", traceId)
      .header("x-datadog-parent-id", parentId)
      .header("x-datadog-origin", "rum")
      .header("x-datadog-sampling-priority", "1")
      .get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(2)
    def downstreamTrace = getServiceTraceFromUrl("http://localhost:${httpPorts[0]}/rest-api/returnheaders")
    checkRootSpanPrioritySampling(downstreamTrace, PrioritySampling.USER_KEEP)
    def downstreamHeaders = jsonSlurper.parseText(response.body().string())
    downstreamHeaders["x-datadog-sampling-priority"] == "2"
  }
}
