package datadog.trace.common.writer

import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.PayloadDispatcher
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.Monitoring
import datadog.trace.util.test.DDSpecification
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PayloadDispatcherTest extends DDSpecification {

  @Shared
  Monitoring monitoring = new Monitoring(new NoOpStatsDClient(), 1, TimeUnit.SECONDS)

  def "dropped traces should be reported in the representativeCount"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi)
    PayloadDispatcher dispatcher = new PayloadDispatcher(api, healthMetrics, monitoring)

    when: "traces are reported, serialized, and flushed"
    for (int i = 0; i < droppedTraces; ++i) {
      dispatcher.onTraceDropped()
    }
    for (int i = 0; i < serializedTraces; ++i) {
      dispatcher.addTrace([realSpan()])
    }
    dispatcher.flush()

    then: "the correct representative and trace count are published"
    1 * api.selectTraceMapper() >> new TraceMapperV0_5()
    1 * api.sendSerializedTraces({ it.representativeCount() == droppedTraces + serializedTraces && it.traceCount() == serializedTraces }) >> DDAgentApi.Response.success(200)

    where:
    droppedTraces | serializedTraces
    1             | 1
    10            | 1
    10            | 10
  }

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
      dispatcher.onTraceDropped()
      dispatcher.addTrace(trace)
    }

    then: "the dispatcher has flushed"
    flushed.get()

    where:
    traceMapper << [new TraceMapperV0_5(), new TraceMapperV0_4()]
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
    traceMapper           | traceCount
    new TraceMapperV0_4() | 1
    new TraceMapperV0_4() | 10
    new TraceMapperV0_4() | 100
    new TraceMapperV0_5() | 1
    new TraceMapperV0_5() | 10
    new TraceMapperV0_5() | 100
  }

  def "should report failed request to monitor"() {
    setup:
    HealthMetrics healthMetrics = Mock(HealthMetrics)
    DDAgentApi api = Mock(DDAgentApi)
    PayloadDispatcher dispatcher = new PayloadDispatcher(api, healthMetrics, monitoring)
    List<DDSpan> trace = [realSpan()]
    when:
    for (int i = 0; i < droppedTraces; ++i) {
      dispatcher.onTraceDropped()
    }
    for (int i = 0; i < traceCount; ++i) {
      dispatcher.addTrace(trace)
    }
    dispatcher.flush()
    then:
    1 * healthMetrics.onSerialize({ it > 0 })
    1 * api.selectTraceMapper() >> traceMapper
    1 * api.sendSerializedTraces({ it.traceCount() == traceCount && it.representativeCount() == droppedTraces + traceCount }) >> DDAgentApi.Response.failed(400)

    where:
    traceMapper           | traceCount | droppedTraces
    new TraceMapperV0_4() | 1          | 0
    new TraceMapperV0_4() | 1          | 1
    new TraceMapperV0_4() | 1          | 10
    new TraceMapperV0_4() | 10         | 10
    new TraceMapperV0_4() | 10         | 100
    new TraceMapperV0_4() | 100        | 100
    new TraceMapperV0_4() | 100        | 1000
    new TraceMapperV0_5() | 1          | 0
    new TraceMapperV0_5() | 1          | 1
    new TraceMapperV0_5() | 1          | 10
    new TraceMapperV0_5() | 10         | 10
    new TraceMapperV0_5() | 10         | 100
    new TraceMapperV0_5() | 100        | 100
    new TraceMapperV0_5() | 100        | 1000
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
    dispatcher.droppedCount.get() == 1
  }


  def realSpan() {
    return new DDSpan(0, new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      "",
      "",
      "",
      PrioritySampling.UNSET,
      "",
      [:],
      false,
      "",
      0,
      Mock(PendingTrace),
      Mock(CoreTracer),
      [:]))
  }

}
