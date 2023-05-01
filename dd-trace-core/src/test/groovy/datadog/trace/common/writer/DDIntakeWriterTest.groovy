package datadog.trace.common.writer

import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.StatsDClient
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.common.writer.ddintake.DDIntakeApi
import datadog.trace.common.writer.ddintake.DDIntakeMapperDiscovery
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.MonitoringImpl
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.test.DDCoreSpecification
import spock.lang.Subject

import java.util.concurrent.TimeUnit

import static datadog.trace.common.writer.ddagent.PrioritizationStrategy.PublishResult.*

class DDIntakeWriterTest extends DDCoreSpecification{

  def api = Mock(DDIntakeApi)
  def healthMetrics = Mock(HealthMetrics)
  def monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
  def worker = Mock(TraceProcessingWorker)
  def mapperDiscovery = Mock(DDIntakeMapperDiscovery)

  @Subject
  def writer = new DDIntakeWriter(api, healthMetrics, monitoring, worker, mapperDiscovery)

  // Only used to create spans
  def dummyTracer = tracerBuilder().writer(new ListWriter()).build()

  def cleanup() {
    writer.close()
    dummyTracer.close()
  }

  def "test writer builder"() {
    when:
    def writer = DDIntakeWriter.builder().apiKey("some-apikey").build()

    then:
    writer != null
  }

  def "test writer.start"() {
    when:
    writer.start()

    then:
    1 * healthMetrics.start()
    1 * worker.start()
    1 * worker.getCapacity() >> capacity
    1 * healthMetrics.onStart(capacity)
    0 * _

    where:
    capacity = 5
  }

  def "test writer.flush"() {
    when:
    writer.flush()

    then:
    1 * worker.flush(1, TimeUnit.SECONDS) >> true
    1 * healthMetrics.onFlush(false)
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

  def "test writer.write publish succeeds"() {
    setup:
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when: "publish succeeds"
    writer.write(trace)

    then: "monitor is notified of successful publication"
    1 * worker.publish(_, _, trace) >> ENQUEUED_FOR_SERIALIZATION
    1 * healthMetrics.onPublish(trace, _)
    _ * worker.flush(1, TimeUnit.SECONDS)
    0 * _
  }

  def "test writer.write publish for single span sampling"() {
    setup:
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when: "publish succeeds"
    writer.write(trace)

    then: "monitor is notified of successful publication"
    1 * worker.publish(_, _, trace) >> ENQUEUED_FOR_SINGLE_SPAN_SAMPLING
    // shouldn't call monitor.onPublish
    _ * worker.flush(1, TimeUnit.SECONDS)
    0 * _
  }

  def "test writer.write publish fails"() {
    setup:
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when: "publish fails"
    writer.write(trace)

    then: "monitor is notified of unsuccessful publication"
    1 * worker.publish(_, _, trace) >> publishResult
    1 * healthMetrics.onFailedPublish(_,1)
    _ * worker.flush(1, TimeUnit.SECONDS)
    0 * _

    where:
    publishResult << [DROPPED_BUFFER_OVERFLOW, DROPPED_BY_POLICY]
  }

  def "empty traces should be reported as failures"() {
    when: "trace is empty"
    writer.write([])

    then: "monitor is notified of unsuccessful publication"
    1 * healthMetrics.onFailedPublish(_,0)
    _ * worker.flush(1, TimeUnit.SECONDS)
    0 * _
  }

  def "test writer.write closed"() {
    setup:
    writer.close()
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when:
    writer.write(trace)

    then:
    1 * healthMetrics.onFailedPublish(_,1)
    _ * worker.flush(1, TimeUnit.SECONDS)
    0 * _
  }

  def "dropped trace is counted"() {
    setup:
    def dispatcher = Mock(PayloadDispatcher)
    def writer = new DDIntakeWriter(api, healthMetrics, dispatcher, worker, true)
    def p0 = newSpan()
    p0.setSamplingPriority(PrioritySampling.SAMPLER_DROP)
    def trace = [p0, newSpan()]

    when:
    writer.write(trace)

    then:
    1 * worker.publish(trace[0], PrioritySampling.SAMPLER_DROP, trace) >> publishResult
    1 * dispatcher.onDroppedTrace(trace.size())

    where:
    publishResult << [DROPPED_BY_POLICY, DROPPED_BUFFER_OVERFLOW]
  }

  def newSpan() {
    CoreTracer tracer = Mock(CoreTracer)
    PendingTrace trace = Mock(PendingTrace)
    trace.mapServiceName(_) >> { String serviceName -> serviceName }
    trace.getTracer() >> tracer
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
      AgentTracer.NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty())
    return new DDSpan(0, context)
  }

}
