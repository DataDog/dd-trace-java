package datadog.trace.api.writer

import com.timgroup.statsd.StatsDClient
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
import java.util.concurrent.atomic.AtomicInteger

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
      1G,
      1G,
      0G,
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
      1G,
      1G,
      0G,
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
    def first = true
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
        List<byte[]> traces) {
        // simulating a communication failure to a server
        return DDApi.Response.failed(new IOException("comm error"))
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

  def "slow response test"() {
    def numPublished = 0
    def numFailedPublish = 0

    setup:
    def minimalTrace = createMinimalTrace()

    // Need to set-up a dummy agent for the final send callback to work
    def first = true
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          // DDApi sniffs for end point existence, so respond quickly the first time
          // then slowly thereafter

          if (!first) {
            // Long enough to stall the pipeline, but not long enough to fail
            Thread.sleep(2_500)
          }
          response.status(200).send()
          first = false
        }
      }
    }
    def api = new DDApi("localhost", agent.address.port, null)

    // This test focuses just on failed publish, so not verifying every callback
    def monitor = Stub(DDAgentWriter.Monitor)
    monitor.onPublish(_, _) >> {
      numPublished += 1
    }
    monitor.onFailedPublish(_, _) >> {
      numFailedPublish += 1
    }

    def bufferSize = 32
    def writer = new DDAgentWriter(api, monitor, bufferSize, DDAgentWriter.FLUSH_PAYLOAD_DELAY)
    writer.start()

    when:
    // write & flush a single trace -- the slow agent response will cause
    // additional writes to back-up the sending queue
    writer.write(minimalTrace)
    writer.flush()

    then:
    numPublished == 1
    numFailedPublish == 0

    when:
    // send many traces to flood the sender queue...
    (1..20).each {
      writer.write(minimalTrace)
    }

    then:
    // might spill back into the Disruptor slightly, but sender queue is currently unbounded
    numPublished == 1 + 20
    numFailedPublish == 0

    when:
    // now, fill-up the disruptor buffer as well
    (1..bufferSize * 2).each {
      writer.write(minimalTrace)
    }

    then:
    // Disruptor still doesn't reject because the sender queue is unbounded
    (numPublished + numFailedPublish) == (1 + 20 + bufferSize * 2)
    numFailedPublish >= 0

    cleanup:
    writer.close()
    agent.close()
  }

  def "multi threaded"() {
    def numPublished = new AtomicInteger(0)
    def numFailedPublish = new AtomicInteger(0)
    def numRepSent = new AtomicInteger(0)

    setup:
    def minimalTrace = createMinimalTrace()

    // Need to set-up a dummy agent for the final send callback to work
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          response.status(200).send()
        }
      }
    }
    def api = new DDApi("localhost", agent.address.port, null)

    // This test focuses just on failed publish, so not verifying every callback
    def monitor = Stub(DDAgentWriter.Monitor)
    monitor.onPublish(_, _) >> {
      numPublished.incrementAndGet()
    }
    monitor.onFailedPublish(_, _) >> {
      numFailedPublish.incrementAndGet()
    }
    monitor.onSend(_, _, _, _) >> { writer, repCount, sizeInBytes, response ->
      numRepSent.addAndGet(repCount)
    }

    def writer = new DDAgentWriter(api, monitor)
    writer.start()

    when:
    def producer = {
      (1..100).each {
        writer.write(minimalTrace)
      }
    } as Runnable

    def t1 = new Thread(producer)
    t1.start()

    def t2 = new Thread(producer)
    t2.start()

    t1.join()
    t2.join()

    writer.flush()

    then:
    def totalTraces = 100 + 100
    numPublished.get() == totalTraces
    numRepSent.get() == totalTraces

    cleanup:
    writer.close()
    agent.close()
  }

  def "statsd success"() {
    def numTracesAccepted = 0
    def numRequests = 0
    def numResponses = 0

    setup:
    def minimalTrace = createMinimalTrace()

    // Need to set-up a dummy agent for the final send callback to work
    def agent = httpServer {
      handlers {
        put("v0.4/traces") {
          response.status(200).send()
        }
      }
    }
    def api = new DDApi("localhost", agent.address.port, null)

    def statsd = Stub(StatsDClient)
    statsd.incrementCounter("queue.accepted") >> { stat ->
      numTracesAccepted += 1
    }
    statsd.incrementCounter("api.requests") >> { stat ->
      numRequests += 1
    }
    statsd.incrementCounter("api.responses", _) >> { stat, tags ->
      numResponses += 1
    }

    def monitor = new DDAgentWriter.StatsDMonitor(statsd)
    def writer = new DDAgentWriter(api, monitor)
    writer.start()

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    numTracesAccepted == 1
    numRequests == 1
    numResponses == 1

    cleanup:
    agent.close()
    writer.close()
  }

  def "statsd comm failure"() {
    def numRequests = 0
    def numResponses = 0
    def numErrors = 0

    setup:
    def minimalTrace = createMinimalTrace()

    // DQH -- need to set-up a dummy agent for the final send callback to work
    def api = new DDApi("localhost", 8192, null) {
      DDApi.Response sendSerializedTraces(
        int representativeCount,
        Integer sizeInBytes,
        List<byte[]> traces) {
        // simulating a communication failure to a server
        return DDApi.Response.failed(new IOException("comm error"))
      }
    }

    def statsd = Stub(StatsDClient)
    statsd.incrementCounter("api.requests") >> { stat ->
      numRequests += 1
    }
    statsd.incrementCounter("api.responses", _) >> { stat, tags ->
      numResponses += 1
    }
    statsd.incrementCounter("api.errors", _) >> { stat ->
      numErrors += 1
    }

    def monitor = new DDAgentWriter.StatsDMonitor(statsd)
    def writer = new DDAgentWriter(api, monitor)
    writer.start()

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    numRequests == 1
    numResponses == 0
    numErrors == 1

    cleanup:
    writer.close()
  }
}
