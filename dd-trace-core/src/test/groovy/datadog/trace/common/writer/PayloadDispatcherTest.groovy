package datadog.trace.common.writer

import datadog.trace.api.DDId
import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.common.writer.ddagent.Payload
import datadog.trace.common.writer.ddagent.PayloadDispatcher
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.MonitoringImpl
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PayloadDispatcherTest extends DDSpecification {

  @Shared
  MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)

  @Timeout(5)
  def "flush automatically when data limit is breached"() {
    setup:
    AtomicBoolean flushed = new AtomicBoolean()
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentFeaturesDiscovery discovery = Mock(DDAgentFeaturesDiscovery)
    discovery.getTraceEndpoint() >> traceEndpoint
    DDAgentApi api = Mock(DDAgentApi)
    api.sendSerializedTraces(_) >> {
      flushed.set(true)
      return DDAgentApi.Response.success(200)
    }
    PayloadDispatcher dispatcher = new PayloadDispatcher(discovery, api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    while (!flushed.get()) {
      dispatcher.addTrace(trace)
    }

    then: "the dispatcher has flushed"
    flushed.get()

    where:
    traceEndpoint << ["v0.5/traces", "v0.4/traces"]
  }

  def "should flush buffer on demand"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentFeaturesDiscovery discovery = Mock(DDAgentFeaturesDiscovery)
    DDAgentApi api = Mock(DDAgentApi)
    PayloadDispatcher dispatcher = new PayloadDispatcher(discovery, api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    2 * discovery.getTraceEndpoint() >> traceEndpoint
    1 * healthMetrics.onSerialize({ it > 0 })
    1 * api.sendSerializedTraces({ it.traceCount() == traceCount }) >> DDAgentApi.Response.success(200)

    where:
    traceEndpoint | traceCount
    "v0.4/traces" | 1
    "v0.4/traces" | 10
    "v0.4/traces" | 100
    "v0.5/traces" | 1
    "v0.5/traces" | 10
    "v0.5/traces" | 100
  }

  def "should report failed request to monitor"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentFeaturesDiscovery discovery = Mock(DDAgentFeaturesDiscovery)
    DDAgentApi api = Mock(DDAgentApi)
    PayloadDispatcher dispatcher = new PayloadDispatcher(discovery, api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    2 * discovery.getTraceEndpoint() >> traceEndpoint
    1 * healthMetrics.onSerialize({ it > 0 })
    1 * api.sendSerializedTraces({ it.traceCount() == traceCount }) >> DDAgentApi.Response.failed(400)

    where:
    traceEndpoint | traceCount
    "v0.4/traces" | 1
    "v0.4/traces" | 10
    "v0.4/traces" | 100
    "v0.5/traces" | 1
    "v0.5/traces" | 10
    "v0.5/traces" | 100
  }

  def "should drop trace when there is no agent connectivity"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi)
    DDAgentFeaturesDiscovery discovery = Mock(DDAgentFeaturesDiscovery)
    PayloadDispatcher dispatcher = new PayloadDispatcher(discovery, api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    discovery.getTraceEndpoint() >> null
    when:
    dispatcher.addTrace(trace)
    then:
    1 * healthMetrics.onFailedPublish(PrioritySampling.UNSET)
  }

  def "trace and span counts are reset after access"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi)
    DDAgentFeaturesDiscovery discovery = Mock(DDAgentFeaturesDiscovery) {
      it.getTraceEndpoint() >> "v0.4/traces"
    }
    PayloadDispatcher dispatcher = new PayloadDispatcher(discovery, api, healthMetrics, monitoring)

    when:
    dispatcher.addTrace([])
    dispatcher.onDroppedTrace(20)
    dispatcher.onDroppedTrace(2)
    Payload payload = dispatcher.newPayload(1, ByteBuffer.allocate(0))
    then:
    payload.droppedSpans() == 22
    payload.droppedTraces() == 2
    when:
    Payload newPayload = dispatcher.newPayload(1, ByteBuffer.allocate(0))
    then:
    newPayload.droppedSpans() == 0
    newPayload.droppedTraces() == 0
  }


  def realSpan() {
    CoreTracer tracer = Mock(CoreTracer)
    tracer.mapServiceName(_) >> { String serviceName -> serviceName }
    PendingTrace trace = Mock(PendingTrace)
    trace.getTracer() >> tracer
    return new DDSpan(0, new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      null,
      "",
      "",
      "",
      PrioritySampling.UNSET,
      SamplingMechanism.UNKNOWN,
      "",
      [:],
      false,
      "",
      0,
      trace,
      null,
      false))
  }
}
