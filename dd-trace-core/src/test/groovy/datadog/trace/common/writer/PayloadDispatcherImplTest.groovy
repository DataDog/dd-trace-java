package datadog.trace.common.writer

import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.statsd.StatsDClient
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.DDAgentMapperDiscovery
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PayloadDispatcherImplTest extends DDSpecification {

  @Shared
  MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)

  @Timeout(10)
  def "flush automatically when data limit is breached"() {
    setup:
    AtomicBoolean flushed = new AtomicBoolean()
    HealthMetrics healthMetrics = Stub(HealthMetrics)
    DDAgentFeaturesDiscovery discovery = Stub(DDAgentFeaturesDiscovery)
    discovery.getTraceEndpoint() >> traceEndpoint
    DDAgentApi api = Stub(DDAgentApi)
    api.sendSerializedTraces(_) >> {
      flushed.set(true)
      return RemoteApi.Response.success(200)
    }
    PayloadDispatcherImpl dispatcher = new PayloadDispatcherImpl(new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring)
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
    PayloadDispatcherImpl dispatcher = new PayloadDispatcherImpl(new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    2 * discovery.getTraceEndpoint() >> traceEndpoint
    1 * healthMetrics.onSerialize({ it > 0 })
    1 * api.sendSerializedTraces({ it.traceCount() == traceCount }) >> RemoteApi.Response.success(200)

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
    PayloadDispatcherImpl dispatcher = new PayloadDispatcherImpl(new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    2 * discovery.getTraceEndpoint() >> traceEndpoint
    1 * healthMetrics.onSerialize({ it > 0 })
    1 * api.sendSerializedTraces({ it.traceCount() == traceCount }) >> RemoteApi.Response.failed(400)

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
    PayloadDispatcherImpl dispatcher = new PayloadDispatcherImpl(new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    discovery.getTraceEndpoint() >> null
    when:
    dispatcher.addTrace(trace)
    then:
    1 * healthMetrics.onFailedPublish(PrioritySampling.UNSET,_)
  }

  def "trace and span counts are reset after access"() {
    setup:
    HealthMetrics healthMetrics = Stub(HealthMetrics)
    DDAgentApi api = Stub(DDAgentApi)
    DDAgentFeaturesDiscovery discovery = Mock(DDAgentFeaturesDiscovery) {
      it.getTraceEndpoint() >> "v0.4/traces"
    }
    PayloadDispatcherImpl dispatcher = new PayloadDispatcherImpl(new DDAgentMapperDiscovery(discovery), api, healthMetrics, monitoring)

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
    CoreTracer tracer = Stub(CoreTracer)
    PendingTrace trace = Stub(PendingTrace)
    trace.getTracer() >> tracer
    trace.mapServiceName(_) >> { String serviceName -> serviceName }
    def context = new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
      null,
      "",
      "",
      "",
      PrioritySampling.UNSET,
      "",
      [:],
      false,
      "",
      0,
      trace,
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty())
    return new DDSpan("test", 0, context, null)
  }
}
