package datadog.smoketest.asmstandalonebilling

import datadog.trace.api.sampling.PrioritySampling
import okhttp3.Request

class AsmStandaloneBillingConerCasesSmokeTest extends AbstractAsmStandaloneBillingSmokeTest {

  private static final String STANDALONE_BILLING_SERVICE_NAME = "asm-standalone-billing-corner-cases-smoketest-app"
  private static final String ASM_ENABLED_SERVICE_NAME = "asm-enabled-corner-cases-smoketest-app"

  static final String[] STANDALONE_BILLING_PROPERTIES = [
    "-Ddd.experimental.appsec.standalone.enabled=true",
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    //"-Ddd.appsec.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${STANDALONE_BILLING_SERVICE_NAME}",
  ]

  static final String[] ASM_ENABLED_PROPERTIES = [
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    //"-Ddd.appsec.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${ASM_ENABLED_SERVICE_NAME}",
  ]

  protected int numberOfProcesses() {
    return 2
  }

  @Override
  ProcessBuilder createProcessBuilder(int processIndex) {
    if(processIndex == 0){
      return createProcess(processIndex, STANDALONE_BILLING_PROPERTIES)
    }
    return createProcess(processIndex, ASM_ENABLED_PROPERTIES)
  }

  void 'test'() {
    setup:
    final vulnerableUrl = "http://localhost:${httpPorts[1]}/rest-api/iast?injection=xss"
    final url1 = "http://localhost:${httpPorts[0]}/rest-api/greetings"
    final request1 = new Request.Builder().url(url1).get().build()
    final upstreamUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings?url=${vulnerableUrl}"
    final upstreamRequest = new Request.Builder().url(upstreamUrl).get().build()

    when: "firs request upstream service"
    final response1 = client.newCall(request1).execute()

    then: "First trace should be sampled with sampler keep as it's the first span checked in a minute"
    response1.successful
    waitForTraceCount(1)
    checkRootSpanPrioritySampling(traces[0], PrioritySampling.SAMPLER_KEEP)
    traces.clear()

    when: "Second request upstream service"
    final upstreamResponse = client.newCall(upstreamRequest).execute()

    then: "This trace should enter into the sampling mechanism and have a root span with SAMPLER_DROP sampling priority as it's not the first span checked in a minute"
    upstreamResponse.successful
    waitForTraceCount(2)
    checkRootSpanPrioritySampling(getServiceTrace(STANDALONE_BILLING_SERVICE_NAME), PrioritySampling.SAMPLER_DROP)

    and: "Upstream sampler drop ca be overwritten with USER_KEEP for ASM Events"
    def asmTrace = getServiceTrace(ASM_ENABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(asmTrace, PrioritySampling.USER_KEEP)
    hasASMEvents(asmTrace)

  }

  void "Check that a malicious x-datadog-sampling-priority header can't drop traces with ASM events"() {
    setup:
    final vulnerableUrl = "http://localhost:${httpPorts[1]}/rest-api/iast?injection=xss"
    final request = new Request.Builder().url(vulnerableUrl)
      .header("x-datadog-sampling-priority", "-1")
      .header("x-datadog-trace-id", "666")
      .header("x-datadog-parent-id", "777")
      .header("X-Forwarded-For", "8.8.8.8")
      .get().build()

    when:
    final response1 = client.newCall(request).execute()

    then: "First trace should be sampled with sampler keep as it's the first span checked in a minute"
    response1.successful
    waitForTraceCount(1)
    checkRootSpanPrioritySampling(traces[0], PrioritySampling.USER_KEEP)

  }


}
