package datadog.smoketest.apmtracingdisabled

import datadog.trace.api.sampling.PrioritySampling
import okhttp3.Request

class ApmTracingDisabledMatrixSmokeTest extends AbstractApmTracingDisabledSmokeTest {

  static final String APM_TRACING_DISABLED_SERVICE_NAME = "apm-tracing-disabled-matrix-smoketest-app"
  static final String APM_TRACING_DISABLED_SERVICE_NAME_2 = "apm-tracing-disabled-matrix-smoketest-app2"
  static final String APM_ENABLED_SERVICE_NAME = "apm-enabled-matrix-smoketest-app"
  static final String ASM_ENABLED_SERVICE_NAME = "asm-enabled-matrix-smoketest-app"

  static final String[] APM_TRACING_DISABLED_PROPERTIES = [
    "-Ddd.apm.tracing.enabled=false",
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${APM_TRACING_DISABLED_SERVICE_NAME}",
  ]

  static final String[] APM_TRACING_DISABLED_PROPERTIES_2 = [
    "-Ddd.apm.tracing.enabled=false",
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${APM_TRACING_DISABLED_SERVICE_NAME_2}",
  ]

  static final String[] APM_ENABLED_PROPERTIES = ["-Ddd.service.name=${APM_ENABLED_SERVICE_NAME}", "-Ddd.trace.tracer.metrics.enabled=true",]

  static final String[] ASM_ENABLED_PROPERTIES = [
    "-Ddd.iast.enabled=true",
    "-Ddd.iast.detection.mode=FULL",
    "-Ddd.iast.debug.enabled=true",
    "-Ddd.trace.tracer.metrics.enabled=true",
    "-Ddd.service.name=${ASM_ENABLED_SERVICE_NAME}",
  ]

  protected int numberOfProcesses() {
    return 4
  }

  @Override
  ProcessBuilder createProcessBuilder(int processIndex) {
    switch (processIndex) {
      case 0:
        return createProcess(processIndex, APM_TRACING_DISABLED_PROPERTIES)
      case 1:
        return createProcess(processIndex, APM_ENABLED_PROPERTIES)
      case 2:
        return createProcess(processIndex, ASM_ENABLED_PROPERTIES)
      case 3:
        return createProcess(processIndex, APM_TRACING_DISABLED_PROPERTIES_2)
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
    def upstreamTrace = getServiceTrace(APM_ENABLED_SERVICE_NAME)
    isSampledBySampler(upstreamTrace)
    !hasAppsecPropagationTag (upstreamTrace)
    !hasApmDisabledTag (upstreamTrace)

    and:"No ASM events, resulting in the local sampling decision"
    def standAloneBillingTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME)
    isSampledBySampler(standAloneBillingTrace)
    !hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)

    and:"No assumption can be done about the setup of the downstream services, so the default APM tracing behavior must be kept"
    def downstreamTrace = getServiceTrace(ASM_ENABLED_SERVICE_NAME)
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
    def upstreamTrace = getServiceTrace(APM_ENABLED_SERVICE_NAME)
    isSampledBySampler(upstreamTrace)
    !hasAppsecPropagationTag (upstreamTrace)
    !hasApmDisabledTag (upstreamTrace)

    and:"ASM events"
    def standAloneBillingTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)

    and:"ASM requires the distributed trace"
    def downstreamTrace = getServiceTrace(ASM_ENABLED_SERVICE_NAME)
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
    def upstreamTrace = getServiceTrace(APM_ENABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(upstreamTrace, PrioritySampling.USER_KEEP)
    !hasAppsecPropagationTag (upstreamTrace)
    !hasApmDisabledTag (upstreamTrace)

    and:"No ASM events, resulting in the local sampling decision"
    def standAloneBillingTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME)
    isSampledBySampler(standAloneBillingTrace)
    !hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)

    and:"Only the local trace is expected to be “muted” and no assumption must be done about the downstream one, so the sampling decision propagated by upstream services must be honored"
    def downstreamTrace = getServiceTrace(ASM_ENABLED_SERVICE_NAME)
    isSampledBySampler(downstreamTrace)
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
    def upstreamTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME_2)
    checkRootSpanPrioritySampling(upstreamTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (upstreamTrace)
    hasApmDisabledTag (upstreamTrace)

    and:"standalone service must keep the local trace with the local sampling priority"
    def standAloneBillingTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)

    and:"Only the local trace is expected to be “muted” and no assumption must be done about the downstream one, so the sampling decision propagated by upstream services must be honored"
    def downstreamTrace = getServiceTrace(ASM_ENABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(downstreamTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (downstreamTrace)
    !hasApmDisabledTag (downstreamTrace)
  }

  void 'Behave as a non APM-instrumented service: Anything but upstream ASM events - determined by the absence of _dd.p.appsec:1 AND No local ASM security events '() {
    setup:
    final downstreamUrl = "http://localhost:${httpPorts[2]}/rest-api/greetings"
    final standAloneBillingUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings?url=${downstreamUrl}"
    final upstreamUrl = "http://localhost:${httpPorts[1]}/rest-api/greetings?forceKeep=true&url=${standAloneBillingUrl}"
    final request = new Request.Builder().url(upstreamUrl).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(3)

    and: 'No upstream ASM events but force keep span'
    def upstreamTrace = getServiceTrace(APM_ENABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(upstreamTrace, PrioritySampling.USER_KEEP)
    !hasAppsecPropagationTag (upstreamTrace)
    !hasApmDisabledTag (upstreamTrace)
    def upstreamTraceId = getServiceTrace(APM_ENABLED_SERVICE_NAME).spans[0].traceId

    and: 'No ASM events, resulting in the local sampling decision'
    def standAloneBillingTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME)
    isSampledBySampler(standAloneBillingTrace)
    !hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)
    def standAloneBillingTraceId = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME).spans[0].traceId
    upstreamTraceId == standAloneBillingTraceId //There is propagation

    and: 'Propagation is stopped'
    def downstreamTrace = getServiceTrace(ASM_ENABLED_SERVICE_NAME)
    isSampledBySampler(downstreamTrace)
    !hasAppsecPropagationTag (downstreamTrace)
    !hasApmDisabledTag (downstreamTrace)
    def downstreamTraceId = getServiceTrace(ASM_ENABLED_SERVICE_NAME).spans[0].traceId
    standAloneBillingTraceId != downstreamTraceId //There is no propagation
  }

  void 'Behave as an APM-instrumented service: Upstream sampling decision involving ASM with the presence of _dd.p.appsec:1 along with Force Keep (2)'(){
    setup:
    final downstreamUrl = "http://localhost:${httpPorts[2]}/rest-api/greetings"
    final standAloneBillingUrl = "http://localhost:${httpPorts[0]}/rest-api/greetings?url=${downstreamUrl}"
    final upstreamUrl = "http://localhost:${httpPorts[3]}/rest-api/iast?injection=vulnerable&url=${standAloneBillingUrl}"
    final request = new Request.Builder().url(upstreamUrl).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(3)

    and: 'Upstream ASM events'
    def upstreamTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME_2)
    checkRootSpanPrioritySampling(upstreamTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (upstreamTrace)
    hasApmDisabledTag (upstreamTrace)
    def upstreamTraceId = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME_2).spans[0].traceId

    and: 'No ASM events, resulting in the local sampling decision'
    def standAloneBillingTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)
    def standAloneBillingTraceId = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME).spans[0].traceId
    upstreamTraceId == standAloneBillingTraceId //There is propagation

    and: 'Default APM distributed tracing behavior with'
    def downstreamTrace = getServiceTrace(ASM_ENABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(downstreamTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (downstreamTrace)
    !hasApmDisabledTag (downstreamTrace)
    def downstreamTraceId = getServiceTrace(ASM_ENABLED_SERVICE_NAME).spans[0].traceId
    standAloneBillingTraceId == downstreamTraceId //There is propagation
  }

  void 'Behave as an APM-instrumented service: Upstream sampling decision involving ASM with the presence of _dd.p.appsec:1 along with Force Keep (2)'(){
    setup:
    final downstreamUrl = "http://localhost:${httpPorts[2]}/rest-api/greetings"
    final standAloneBillingUrl = "http://localhost:${httpPorts[0]}/rest-api/iast?injection=vulnerable%26url=${downstreamUrl}"
    final upstreamUrl = "http://localhost:${httpPorts[1]}/rest-api/greetings?&url=${standAloneBillingUrl}"
    final request = new Request.Builder().url(upstreamUrl).get().build()

    when:
    final response = client.newCall(request).execute()

    then:
    response.successful
    waitForTraceCount(3)

    and: 'No upstream ASM events'
    def upstreamTrace = getServiceTrace(APM_ENABLED_SERVICE_NAME)
    isSampledBySampler (upstreamTrace)
    !hasAppsecPropagationTag (upstreamTrace)
    !hasApmDisabledTag (upstreamTrace)
    def upstreamTraceId = getServiceTrace(APM_ENABLED_SERVICE_NAME).spans[0].traceId

    and: 'ASM events, resulting in force keep and appsec propagation'
    def standAloneBillingTrace = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(standAloneBillingTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (standAloneBillingTrace)
    hasApmDisabledTag (standAloneBillingTrace)
    def standAloneBillingTraceId = getServiceTrace(APM_TRACING_DISABLED_SERVICE_NAME).spans[0].traceId
    upstreamTraceId == standAloneBillingTraceId //There is propagation

    and: 'Default APM distributed tracing behavior with'
    def downstreamTrace = getServiceTrace(ASM_ENABLED_SERVICE_NAME)
    checkRootSpanPrioritySampling(downstreamTrace, PrioritySampling.USER_KEEP)
    hasAppsecPropagationTag (downstreamTrace)
    !hasApmDisabledTag (downstreamTrace)
    def downstreamTraceId = getServiceTrace(ASM_ENABLED_SERVICE_NAME).spans[0].traceId
    standAloneBillingTraceId == downstreamTraceId //There is propagation
  }
}
