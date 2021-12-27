package datadog.trace.common.writer

import datadog.trace.api.DDId
import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.sampling.SamplingMechanism
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.trace.common.writer.ddagent.PayloadDispatcher
import datadog.trace.common.writer.ddagent.TraceProcessingWorker
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.MonitoringImpl
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Subject

import java.util.concurrent.TimeUnit

class DDAgentWriterTest extends DDCoreSpecification {

  def discovery = Mock(DDAgentFeaturesDiscovery)
  def api = Mock(DDAgentApi)
  def monitor = Mock(HealthMetrics)
  def worker = Mock(TraceProcessingWorker)
  def monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)

  @Subject
  def writer = new DDAgentWriter(discovery, api, monitor, monitoring, worker)

  // Only used to create spans
  def dummyTracer = tracerBuilder().writer(new ListWriter()).build()

  def cleanup() {
    writer.close()
    dummyTracer.close()
  }

  def "test writer builder"() {
    when:
    def writer = DDAgentWriter.builder().build()

    then:
    writer != null
  }

  def "test writer.start"() {
    when:
    writer.start()

    then:
    1 * monitor.start()
    1 * worker.start()
    1 * worker.getCapacity() >> capacity
    1 * monitor.onStart(capacity)
    0 * _

    where:
    capacity = 5
  }

  def "test writer.start closed"() {
    setup:
    writer.close()

    when:
    writer.start()

    then:
    0 * _
  }

  def "test writer.flush"() {
    when:
    writer.flush()

    then:
    1 * worker.flush(1, TimeUnit.SECONDS) >> true
    1 * monitor.onFlush(false)
    0 * _

    when:
    writer.flush()

    then:
    1 * worker.flush(1, TimeUnit.SECONDS) >> false
    0 * _
  }

  def "test writer.flush closed"() {
    setup:
    writer.close()

    when:
    writer.flush()

    then:
    0 * _
  }

  def "test writer.write"() {
    setup:
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when: "publish succeeds"
    writer.write(trace)

    then: "monitor is notified of successful publication"
    1 * worker.publish(_, _, trace) >> true
    1 * monitor.onPublish(trace, _)
    0 * _

    when: "publish fails"
    writer.write(trace)

    then: "monitor is notified of unsuccessful publication"
    1 * worker.publish(_, _, trace) >> false
    1 * monitor.onFailedPublish(_)
    0 * _
  }

  def "empty traces should be reported as failures"() {
    when: "trace is empty"
    writer.write([])

    then: "monitor is notified of unsuccessful publication"
    1 * monitor.onFailedPublish(_)
    0 * _
  }

  def "test writer.write closed"() {
    setup:
    writer.close()
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when:
    writer.write(trace)

    then:
    1 * monitor.onFailedPublish(_)
    0 * _
  }

  def "dropped trace is counted"() {
    setup:
    def discovery = Mock(DDAgentFeaturesDiscovery)
    def api = Mock(DDAgentApi)
    def worker = Mock(TraceProcessingWorker)
    def monitor = Stub(HealthMetrics)
    def dispatcher = Mock(PayloadDispatcher)
    def writer = new DDAgentWriter(discovery, api, monitor, dispatcher, worker)
    def p0 = newSpan()
    p0.setSamplingPriority(PrioritySampling.SAMPLER_DROP)
    def trace = [p0, newSpan()]

    when:
    writer.write(trace)

    then:
    1 * worker.publish(trace[0], PrioritySampling.SAMPLER_DROP, trace) >> false
    1 * dispatcher.onDroppedTrace(trace.size())
  }

  def newSpan() {
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
