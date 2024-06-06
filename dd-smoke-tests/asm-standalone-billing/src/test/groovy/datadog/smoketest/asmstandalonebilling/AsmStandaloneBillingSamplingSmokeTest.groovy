package datadog.smoketest.asmstandalonebilling

import datadog.trace.api.sampling.PrioritySampling
import okhttp3.Request

class AsmStandaloneBillingSamplingSmokeTest extends AbstractAsmStandaloneBillingSmokeTest {

  @Override
  ProcessBuilder createProcessBuilder(){
    final String[] processProperties = [
      "-Ddd.experimental.appsec.standalone.enabled=true",
      "-Ddd.iast.enabled=true",
      "-Ddd.iast.detection.mode=FULL",
      "-Ddd.iast.debug.enabled=true",
      "-Ddd.service.name=asm-standalone-billing-sampling-spring-smoketest-app",
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
}
