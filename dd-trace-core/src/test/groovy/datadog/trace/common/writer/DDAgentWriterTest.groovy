package datadog.trace.common.writer

import com.timgroup.statsd.NoOpStatsDClient
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.TraceProcessingWorker
import datadog.trace.core.CoreTracer
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.Monitoring
import datadog.trace.test.util.DDSpecification
import spock.lang.Subject

import java.util.concurrent.TimeUnit

class DDAgentWriterTest extends DDSpecification {

  def api = Mock(DDAgentApi)
  def monitor = Mock(HealthMetrics)
  def worker = Mock(TraceProcessingWorker)
  def monitoring = new Monitoring(new NoOpStatsDClient(), 1, TimeUnit.SECONDS)

  @Subject
  def writer = new DDAgentWriter(api, monitor, monitoring, worker)

  // Only used to create spans
  def dummyTracer = CoreTracer.builder().writer(new ListWriter()).build()

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
    1 * worker.publish(_, trace) >> true
    1 * monitor.onPublish(trace, _)
    0 * _

    when: "publish fails"
    writer.write(trace)

    then: "monitor is notified of unsuccessful publication"
    1 * worker.publish(_, trace) >> false
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
}
