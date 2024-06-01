package datadog.smoketest.asmstandalonebilling

import datadog.trace.api.sampling.PrioritySampling
import okhttp3.Request

class AsmStandaloneBillingMatrixSmokeTest extends AbstractAsmStandaloneBillingSmokeTest {

  static final String standAloneBillingServiceName = "asm-standalone-billing-matrix-smoketest-app"
  static final String standAloneBillingServiceName2 = "asm-standalone-billing-matrix-smoketest-app2"
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

  static final String[] standAloneBillingProperties2 = [
    "-Ddd.experimental.appsec.standalone.enabled=true",
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    //"-Ddd.appsec.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${standAloneBillingServiceName2}",
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
    return 4
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
      case 3:
        return createProcess(processIndex, standAloneBillingProperties2)
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

  void 'Test RFC Scenario 2'() {
    setup:
    //No ASM events
    final downstreamUrl = "http://localhost:${httpPorts[2]}/rest-api/greetings"
    //ASM event
    final standAloneBillingUrl = "http://localhost:${httpPorts[0]}/rest-api/iast?injection=vulnerable%26url=${downstreamUrl}"
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

    and:"ASM events"
    def standAloneBillingTrace = getServiceTrace(standAloneBillingServiceName)
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)

    and:"ASM requires the distributed trace"
    def downstreamTrace = getServiceTrace(asmEnabledServiceName)
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (standAloneBillingTrace)
    !hasApmDisabledTag (downstreamTrace)
  }

  void 'Test RFC Scenario 3'() {
    setup:
    //No ASM events
    final downstreamUrl = "http://localhost:${httpPorts[2]}/rest-api/greetings"
    //No ASM events
    final standAloneBillingUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings?url=${downstreamUrl}"
    //upstream service with force keep span
    final upstreamUrl = "http://localhost:${httpPorts[1]}/rest-api/greetings?forceKeep=true&url=${standAloneBillingUrl}"
    final request = new Request.Builder().url(upstreamUrl).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(3)

    and: "Upstream APM service propagating the force keep"
    def upstreamTrace = getServiceTrace(apmEnabledServiceName)
    checkRootSpanPrioritySampling(upstreamTrace, PrioritySampling.USER_KEEP)
    !hasAppsecPropagationTag (upstreamTrace)
    !hasApmDisabledTag (upstreamTrace)

    and:"No ASM events, resulting in the local sampling decision"
    def standAloneBillingTrace = getServiceTrace(standAloneBillingServiceName)
    //TODO Check RFC as it said isSampledBySampler(standAloneBillingTrace) but sampling priority it's set in the upstream service and locked for all downstream processes
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    !hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)

    and:"Only the local trace is expected to be “muted” and no assumption must be done about the downstream one, so the sampling decision propagated by upstream services must be honored"
    def downstreamTrace = getServiceTrace(asmEnabledServiceName)
    checkRootSpanPrioritySampling(downstreamTrace, PrioritySampling.USER_KEEP)
    !hasAppsecPropagationTag (downstreamTrace)
    !hasApmDisabledTag (downstreamTrace)
  }

  void 'Test RFC Scenario 4'() {
    setup:
    //No ASM events
    final downstreamUrl = "http://localhost:${httpPorts[2]}/rest-api/greetings"
    //Stand alone without events
    final standAloneBillingUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings?url=${downstreamUrl}"
    //StandAlone with iast event
    final upstreamUrl = "http://localhost:${httpPorts[3]}/rest-api/iast?injection=vulnerable&url=${standAloneBillingUrl}"
    final request = new Request.Builder().url(upstreamUrl).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(3)

    and: "Upstream standalone ASM service having ASM events result in force keep and propagation of the tag"
    def upstreamTrace = getServiceTrace(standAloneBillingServiceName2)
    checkRootSpanPrioritySampling(upstreamTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (upstreamTrace)
    hasApmDisabledTag (upstreamTrace)

    and:"standalone service must keep the local trace with the local sampling priority"
    def standAloneBillingTrace = getServiceTrace(standAloneBillingServiceName)
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    //TODO Check RFC as it said !hasAppsecPropagationTag (standAloneBillingTrace) but the tag is propagated from the upstream service
    hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)

    and:"Only the local trace is expected to be “muted” and no assumption must be done about the downstream one, so the sampling decision propagated by upstream services must be honored"
    def downstreamTrace = getServiceTrace(asmEnabledServiceName)
    checkRootSpanPrioritySampling(downstreamTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (downstreamTrace)
    !hasApmDisabledTag (downstreamTrace)
  }



}
