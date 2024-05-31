package datadog.smoketest.asmstandalonebilling

import okhttp3.Request

class AsmStandaloneBillingMatrixSmokeTest extends AbstractAsmStandaloneBillingSmokeTest {

  static final String standAloneBillingServiceName = "asm-standalone-billing-matrix-smoketest-app"
  static final String apmEnabledServiceName = "apm-enabled-matrix-smoketest-app"
  static final String asmEnabledServiceName = "asm-enabled-matrix-smoketest-app"

  static final String[] standAloneBillingProperties = [
    "-Ddd.experimental.appsec.standalone.enabled=true",
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    //"-Ddd.appsec.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${standAloneBillingServiceName}",
  ]

  static final String[] apmEnabledProperties = ["-Ddd.service.name=${apmEnabledServiceName}", "-Ddd.trace.tracer.metrics.enabled=true",]

  static final String[] asmEnabledProperties = [
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    //"-Ddd.appsec.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${asmEnabledServiceName}",
  ]

  protected int numberOfProcesses() {
    return 3
  }

  @Override
  ProcessBuilder createProcessBuilder(int processIndex) {
    switch (processIndex) {
      case 0:
        return createProcess(processIndex, standAloneBillingProperties)
      case 1:
        return createProcess(processIndex, apmEnabledProperties)
      case 2:
        return createProcess(processIndex, asmEnabledProperties)
      default:
        throw new IllegalArgumentException("Invalid process index: ${processIndex}")
    }
  }

  void 'Test RFC Scenario 1'() {
    setup:
    //No ASM events
    final downstreamUrl = "http://localhost:${httpPorts[2]}/rest-api/greetings"
    //No ASM events
    final standAloneBillingUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings?url=${downstreamUrl}"
    //No traced upstream service
    final upstreamUrl = "http://localhost:${httpPorts[1]}/rest-api/greetings?url=${standAloneBillingUrl}"
    final request = new Request.Builder().url(upstreamUrl).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(3)

    and: "No traced upstream service, resulting in no propagated sampling decision"
    def upstreamTrace = getServiceTrace(apmEnabledServiceName)
    isSampledBySampler(upstreamTrace)
    !hasAppsecPropagationTag (upstreamTrace)
    !hasApmDisabledTag (upstreamTrace)

    and:"No ASM events, resulting in the local sampling decision"
    def standAloneBillingTrace = getServiceTrace(standAloneBillingServiceName)
    isSampledBySampler(standAloneBillingTrace)
    !hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)

    and:"No assumption can be done about the setup of the downstream services, so the default APM tracing behavior must be kept"
    def downstreamTrace = getServiceTrace(asmEnabledServiceName)
    isSampledBySampler(downstreamTrace)
    !hasAppsecPropagationTag (downstreamTrace)
    !hasApmDisabledTag (downstreamTrace)
  }
}
