package datadog.trace.api.writer

import datadog.opentracing.DDSpan
import datadog.opentracing.DDSpanContext
import datadog.opentracing.DDTracer
import datadog.opentracing.PendingTrace
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.DDAgentWriter
import datadog.trace.common.writer.DDApi
import datadog.trace.util.test.DDSpecification
import spock.lang.Timeout

import java.util.concurrent.TimeUnit

import static datadog.opentracing.SpanFactory.newSpanOf
import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.common.writer.DDAgentWriter.DISRUPTOR_BUFFER_SIZE

@Timeout(20)
class DDAgentWriterTest extends DDSpecification {

  def api = Mock(DDApi)

  def "test happy path"() {
    setup:
    def writer = new DDAgentWriter(api, 2, -1)
    writer.start()

    when:
    writer.write(trace)
    writer.write(trace)
    writer.flush()

    then:
    2 * api.serializeTrace(_) >> { trace -> callRealMethod() }
    1 * api.sendSerializedTraces(2, _, { it.size() == 2 })
    0 * _

    cleanup:
    writer.close()

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
  }

  def "test flood of traces"() {
    setup:
    def writer = new DDAgentWriter(api, disruptorSize, -1)
    writer.start()

    when:
    (1..traceCount).each {
      writer.write(trace)
    }
    writer.flush()

    then:
    _ * api.serializeTrace(_) >> { trace -> callRealMethod() }
    1 * api.sendSerializedTraces(traceCount, _, { it.size() < traceCount })
    0 * _

    cleanup:
    writer.close()

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
    disruptorSize = 2
    traceCount = 100 // Shouldn't trigger payload, but bigger than the disruptor size.
  }

  def "test flush by size"() {
    setup:
    def writer = new DDAgentWriter(api, DISRUPTOR_BUFFER_SIZE, -1)
    def phaser = writer.apiPhaser
    writer.start()
    phaser.register()

    when:
    (1..6).each {
      writer.write(trace)
    }
    // Wait for 2 flushes of 3 by size
    phaser.awaitAdvanceInterruptibly(phaser.arrive())
    phaser.awaitAdvanceInterruptibly(phaser.arriveAndDeregister())

    then:
    6 * api.serializeTrace(_) >> { trace -> callRealMethod() }
    2 * api.sendSerializedTraces(3, _, { it.size() == 3 })

    when:
    (1..2).each {
      writer.write(trace)
    }
    // Flush the remaining 2
    writer.flush()

    then:
    2 * api.serializeTrace(_) >> { trace -> callRealMethod() }
    1 * api.sendSerializedTraces(2, _, { it.size() == 2 })
    0 * _

    cleanup:
    writer.close()

    where:
    span = [newSpanOf(0, "fixed-thread-name")]
    trace = (0..10000).collect { span }
  }

  def "test flush by time"() {
    setup:
    def writer = new DDAgentWriter(api)
    def phaser = writer.apiPhaser
    phaser.register()
    writer.start()
    writer.flush()

    when:
    (1..5).each {
      writer.write(trace)
    }
    phaser.awaitAdvanceInterruptibly(phaser.arriveAndDeregister())

    then:
    5 * api.serializeTrace(_) >> { trace -> callRealMethod() }
    1 * api.sendSerializedTraces(5, _, { it.size() == 5 })
    0 * _

    cleanup:
    writer.close()

    where:
    span = [newSpanOf(0, "fixed-thread-name")]
    trace = (1..10).collect { span }
  }

  def "test default buffer size"() {
    setup:
    def writer = new DDAgentWriter(api, DISRUPTOR_BUFFER_SIZE, -1)
    writer.start()

    when:
    (0..maxedPayloadTraceCount).each {
      writer.write(minimalTrace)
      def start = System.nanoTime()
      // (consumer processes a trace in about 20 microseconds
      while (System.nanoTime() - start < TimeUnit.MICROSECONDS.toNanos(100)) {
        // Busywait because we don't want to fill up the ring buffer
      }
    }
    writer.flush()

    then:
    (maxedPayloadTraceCount + 1) * api.serializeTrace(_) >> { trace -> callRealMethod() }
    1 * api.sendSerializedTraces(maxedPayloadTraceCount, _, { it.size() == maxedPayloadTraceCount })

    cleanup:
    writer.close()

    where:
    minimalContext = new DDSpanContext(
      "1",
      "1",
      "0",
      "",
      "",
      "",
      PrioritySampling.UNSET,
      "",
      Collections.emptyMap(),
      false,
      "",
      Collections.emptyMap(),
      Mock(PendingTrace),
      Mock(DDTracer))
    minimalSpan = new DDSpan(0, minimalContext)
    minimalTrace = [minimalSpan]
    traceSize = DDApi.OBJECT_MAPPER.writeValueAsBytes(minimalTrace).length
    maxedPayloadTraceCount = ((int) (DDAgentWriter.FLUSH_PAYLOAD_BYTES / traceSize)) + 1
  }

  def "check that are no interactions after close"() {

    setup:
    def writer = new DDAgentWriter(api)
    writer.start()

    when:
    writer.close()
    writer.write([])
    writer.flush()

    then:
    0 * _
    writer.traceCount.get() == 0
  }

  def createMinimalTrace() {
    def minimalContext = new DDSpanContext(
      "1",
      "1",
      "0",
      "",
      "",
      "",
      PrioritySampling.UNSET,
      "",
      Collections.emptyMap(),
      false,
      "",
      Collections.emptyMap(),
      Mock(PendingTrace),
      Mock(DDTracer))
    def minimalSpan = new DDSpan(0, minimalContext)
    def minimalTrace = [minimalSpan]

    return minimalTrace
  }

  def "monitor happy path"() {
    setup:
    def minimalTrace = createMinimalTrace()

    // DQH -- need to set-up a dummy agent for the final send callback to work
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          response.status(200).send()
        }
      }
    }
    def api = new DDApi("localhost", agent.address.port, null)
    def monitor = Mock(DDAgentWriter.Monitor)
    def writer = new DDAgentWriter(api, monitor)

    when:
    writer.start()

    then:
    1 * monitor.onStart(writer)

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    1 * monitor.onPublish(writer, minimalTrace)
    1 * monitor.onSerialize(writer, minimalTrace, _)
    1 * monitor.onScheduleFlush(writer, _)
    1 * monitor.onSend(writer, 1, _, { response -> response.success() && response.status() == 200 })

    when:
    writer.close()

    then:
    1 * monitor.onShutdown(writer, true)

    cleanup:
    agent.close()
  }

  def "monitor agent returns error"() {
    setup:
    def minimalTrace = createMinimalTrace()

    // DQH -- need to set-up a dummy agent for the final send callback to work
    def first = true;
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          // DQH - DDApi sniffs for end point existence, so respond with 200 the first time
          if (first) {
            response.status(200).send()
            first = false
          } else {
            response.status(500).send()
          }
        }
      }
    }
    def api = new DDApi("localhost", agent.address.port, null)
    def monitor = Mock(DDAgentWriter.Monitor)
    def writer = new DDAgentWriter(api, monitor)

    when:
    writer.start()

    then:
    1 * monitor.onStart(writer)

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    1 * monitor.onPublish(writer, minimalTrace)
    1 * monitor.onSerialize(writer, minimalTrace, _)
    1 * monitor.onScheduleFlush(writer, _)
    1 * monitor.onFailedSend(writer, 1, _, { response -> !response.success() && response.status() == 500 })

    when:
    writer.close()

    then:
    1 * monitor.onShutdown(writer, true)

    cleanup:
    agent.close()
  }

  def "unreachable agent test"() {
    setup:
    def minimalTrace = createMinimalTrace()

    def api = new DDApi("localhost", 8192, null) {
      DDApi.Response sendSerializedTraces(
        int representativeCount,
        Integer sizeInBytes,
        List<byte[]> traces)
      {
        // simulating a communication failure to a server
        return DDApi.Response.failed(new IOException("comm error"));
      }
    }
    def monitor = Mock(DDAgentWriter.Monitor)
    def writer = new DDAgentWriter(api, monitor)

    when:
    writer.start()

    then:
    1 * monitor.onStart(writer)

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    1 * monitor.onPublish(writer, minimalTrace)
    1 * monitor.onSerialize(writer, minimalTrace, _)
    1 * monitor.onScheduleFlush(writer, _)
    1 * monitor.onFailedSend(writer, 1, _, { response -> !response.success() && response.status() == null })

    when:
    writer.close()

    then:
    1 * monitor.onShutdown(writer, true)
  }
}
