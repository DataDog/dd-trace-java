package datadog.trace.common.writer


import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.Monitor
import datadog.trace.common.writer.ddagent.TraceProcessingDisruptor
import datadog.trace.util.test.DDSpecification
import spock.lang.Subject

import java.util.concurrent.TimeUnit

import static datadog.trace.core.SpanFactory.newSpanOf

class DDAgentWriterTest extends DDSpecification {

  def api = Mock(DDAgentApi)
  def monitor = Mock(Monitor)
  def disruptor = Mock(TraceProcessingDisruptor)

  @Subject
  def writer = new DDAgentWriter(api, monitor, disruptor)

  def setup() {
    assert writer.traceCount.get() == 0
  }

  def "test writer.start"() {
    when:
    writer.start()

    then:
    1 * disruptor.start()
    1 * monitor.onStart(writer)
    0 * _
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
    1 * disruptor.flush(1, TimeUnit.SECONDS) >> true
    1 * monitor.onFlush(writer, false)
    0 * _

    when:
    writer.flush()

    then:
    1 * disruptor.flush(1, TimeUnit.SECONDS) >> false
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
    when: "publish succeeds"
    writer.traceCount.set(count)
    writer.write(trace)

    then: "traceCount is reset"
    writer.traceCount.get() == (resetsCount ? 0 : count)
    1 * disruptor.publish(trace, expected) >> true
    1 * monitor.onPublish(writer, trace)
    0 * _

    when: "publish fails"
    writer.traceCount.set(count)
    writer.write(trace)

    then: "traceCount is incremented"
    writer.traceCount.get() == count + 1
    1 * disruptor.publish(trace, expected) >> false
    1 * monitor.onFailedPublish(writer, trace)
    0 * _

    where:
    count | expected | trace
    0     | 1        | [newSpanOf(0, "fixed-thread-name")]
    1     | 2        | [newSpanOf(0, "fixed-thread-name")]
    10    | 11       | [newSpanOf(0, "fixed-thread-name")]
    0     | 1        | []
    1     | 1        | []
    10    | 1        | []

    resetsCount = !trace.isEmpty()
  }

  def "test writer.write closed"() {
    setup:
    writer.close()

    when:
    writer.write(trace)

    then:
    writer.traceCount.get() == 0
    1 * monitor.onFailedPublish(writer, trace)
    0 * _

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
  }
}
