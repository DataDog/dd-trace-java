package datadog.trace.common.writer

import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.Payload
import datadog.trace.common.writer.ddagent.PayloadDispatcher
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.Monitoring
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PayloadDispatcherTest extends DDSpecification {

  @Shared
  Monitoring monitoring = new Monitoring(new NoOpStatsDClient(), 1, TimeUnit.SECONDS)

  @Timeout(5)
  def "flush automatically when data limit is breached"() {
    setup:
    AtomicBoolean flushed = new AtomicBoolean()
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi)
    api.selectTraceMapper() >> traceMapper
    api.sendSerializedTraces(_) >> {
      flushed.set(true)
      return DDAgentApi.Response.success(200)
    }
    PayloadDispatcher dispatcher = new PayloadDispatcher(api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    while (!flushed.get()) {
      dispatcher.addTrace(trace)
    }

    then: "the dispatcher has flushed"
    flushed.get()

    where:
    traceMapper << [new TraceMapperV0_5(), new TraceMapperV0_4(1024)]
  }

  def "should flush buffer on demand"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi)
    PayloadDispatcher dispatcher = new PayloadDispatcher(api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    1 * healthMetrics.onSerialize({ it > 0 })
    1 * api.selectTraceMapper() >> traceMapper
    1 * api.sendSerializedTraces({ it.traceCount() == traceCount }) >> DDAgentApi.Response.success(200)

    where:
    traceMapper                          | traceCount
    new TraceMapperV0_4(1024)            | 1
    new TraceMapperV0_4(4 * 1024)        | 10
    new TraceMapperV0_4(20 * 1024)       | 100
    new TraceMapperV0_5(1024, 1024)      | 1
    new TraceMapperV0_5(1024, 4096)      | 10
    new TraceMapperV0_5(1024, 20 * 1024) | 100
  }

  def "should report failed request to monitor"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi)
    PayloadDispatcher dispatcher = new PayloadDispatcher(api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    1 * healthMetrics.onSerialize({ it > 0 })
    1 * api.selectTraceMapper() >> traceMapper
    1 * api.sendSerializedTraces({ it.traceCount() == traceCount }) >> DDAgentApi.Response.failed(400)

    where:
    traceMapper                          | traceCount
    new TraceMapperV0_4(1024)            | 1
    new TraceMapperV0_4(4096)            | 10
    new TraceMapperV0_4(20 * 1024)       | 100
    new TraceMapperV0_5(1024, 1024)      | 1
    new TraceMapperV0_5(1024, 4096)      | 10
    new TraceMapperV0_5(1024, 20 * 1024) | 100
  }

  def "should drop trace when there is no agent connectivity"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi)
    PayloadDispatcher dispatcher = new PayloadDispatcher(api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    api.selectTraceMapper() >> null
    when:
    dispatcher.addTrace(trace)
    then:
    1 * healthMetrics.onFailedPublish(PrioritySampling.UNSET)
  }

  def "trace and span counts are reset after access"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi) {
      it.selectTraceMapper() >> new TraceMapperV0_4(0)
    }
    PayloadDispatcher dispatcher = new PayloadDispatcher(api, healthMetrics, monitoring)

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
      "",
      [:],
      false,
      "",
      0,
      trace))
  }

}
