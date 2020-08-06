package datadog.trace.common.writer

import com.timgroup.statsd.StatsDClient
import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.Monitor
import datadog.trace.core.serialization.msgpack.ByteBufferConsumer
import datadog.trace.core.serialization.msgpack.Mapper
import datadog.trace.core.serialization.msgpack.Packer
import datadog.trace.util.test.DDSpecification
import spock.lang.Retry
import spock.lang.Timeout

import java.nio.ByteBuffer
import java.util.concurrent.Phaser
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.common.writer.DDAgentWriter.DISRUPTOR_BUFFER_SIZE
import static datadog.trace.core.SpanFactory.newSpanOf

@Retry
@Timeout(10)
class DDAgentWriterCombinedTest extends DDSpecification {

  def phaser = new Phaser()

  def apiWithVersion(String version) {
    def api = Mock(DDAgentApi)
    api.detectEndpointAndBuildClient() >> version
    api.selectTraceMapper() >> { callRealMethod() }
    return api
  }
  def monitor = Mock(Monitor)

  def setup() {
    // Register for two threads.
    phaser.register()
    phaser.register()
  }

  def "no interactions because of initial flush"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .traceBufferSize(2)
      .flushFrequencySeconds(-1)
      .build()
    writer.start()

    when:
    writer.flush()

    then:
    0 * _

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "test happy path"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .traceBufferSize(2)
      .flushFrequencySeconds(-1)
      .build()
    writer.start()

    when:
    writer.write(trace)
    writer.write(trace)
    writer.flush()

    then:
    1 * api.detectEndpointAndBuildClient() >> agentVersion
    1 * api.selectTraceMapper() >> { callRealMethod() }
    1 * api.sendSerializedTraces({ it.traceCount() == 2 && it.representativeCount() == 2 }) >> DDAgentApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "test flood of traces"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .traceBufferSize(disruptorSize)
      .flushFrequencySeconds(-1)
      .build()
    writer.start()

    when:
    (1..traceCount).each {
      writer.write(trace)
    }
    writer.flush()

    then:
    1 * api.detectEndpointAndBuildClient() >> agentVersion
    1 * api.selectTraceMapper() >> { callRealMethod() }
    1 * api.sendSerializedTraces({ it.traceCount() <= traceCount && it.representativeCount() <= traceCount }) >> DDAgentApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
    disruptorSize = 2
    traceCount = 100 // Shouldn't trigger payload, but bigger than the disruptor size.
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "test flush by time"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .monitor(monitor)
      .flushFrequencySeconds(1)
      .build()
    writer.start()

    when:
    (1..5).each {
      writer.write(trace)
    }
    phaser.awaitAdvanceInterruptibly(phaser.arriveAndDeregister())

    then:
    1 * api.detectEndpointAndBuildClient() >> agentVersion
    1 * api.selectTraceMapper() >> { callRealMethod() }
    1 * monitor.onSerialize(_)
    1 * api.sendSerializedTraces({ it.traceCount() == 5 && it.representativeCount() == 5 }) >> DDAgentApi.Response.success(200)
    _ * monitor.onPublish(_)
    1 * monitor.onSend(_, _, _) >> {
      phaser.arrive()
    }
    0 * _

    cleanup:
    writer.close()

    where:
    span = newSpanOf(0, "fixed-thread-name")
    trace = (1..10).collect { span }
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "test default buffer size"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .traceBufferSize(DISRUPTOR_BUFFER_SIZE)
      .flushFrequencySeconds(-1)
      .build()
    writer.start()

    when:
    def mapper = agentVersion.equals("v0.5/traces") ? new TraceMapperV0_5() : new TraceMapperV0_4()
    int traceSize = calculateSize(minimalTrace, mapper)
    int maxedPayloadTraceCount = ((int) ((mapper.messageBufferSize() - 5) / traceSize))
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
    1 * api.detectEndpointAndBuildClient() >> agentVersion
    1 * api.selectTraceMapper() >> { callRealMethod() }
    1 * api.sendSerializedTraces({ it.traceCount() == maxedPayloadTraceCount && it.representativeCount() == maxedPayloadTraceCount }) >> DDAgentApi.Response.success(200)
    1 * api.sendSerializedTraces({ it.traceCount() == 1 && it.representativeCount() == 1 }) >> DDAgentApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    minimalContext = new DDSpanContext(
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
      [:])
    minimalSpan = new DDSpan(0, minimalContext)
    minimalTrace = [minimalSpan]
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "check that there are no interactions after close"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .monitor(monitor)
      .build()
    writer.start()

    when:
    writer.close()
    writer.write([])
    writer.flush()

    then:
    // this will be checked during flushing
    1 * monitor.onFailedPublish(_)
    1 * monitor.onFlush(_)
    1 * monitor.onShutdown(_)
    0 * _
    writer.traceCount.get() == 0

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def createMinimalTrace() {
    def minimalContext = new DDSpanContext(
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
      [:])
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
        put(agentVersion) {
          response.status(200).send()
        }
      }
    }
    def writer = DDAgentWriter.builder().traceAgentPort(agent.address.port).monitor(monitor).build()

    when:
    writer.start()

    then:
    1 * monitor.onStart(writer.getDisruptorCapacity())

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    1 * monitor.onPublish(minimalTrace)
    1 * monitor.onSerialize(_)
    1 * monitor.onFlush(false)
    1 * monitor.onSend(1, _, { response -> response.success() && response.status() == 200 })

    when:
    writer.close()

    then:
    1 * monitor.onShutdown(true)

    cleanup:
    agent.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "monitor agent returns error"() {
    setup:
    def minimalTrace = createMinimalTrace()

    // DQH -- need to set-up a dummy agent for the final send callback to work
    def first = true
    def agent = httpServer {
      handlers {
        put(agentVersion) {
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
    def writer = DDAgentWriter.builder().traceAgentPort(agent.address.port).monitor(monitor).build()

    when:
    writer.start()

    then:
    1 * monitor.onStart(writer.getDisruptorCapacity())

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    1 * monitor.onPublish(minimalTrace)
    1 * monitor.onSerialize(_)
    1 * monitor.onFlush(false)
    1 * monitor.onFailedSend(1, _, { response -> !response.success() && response.status() == 500 })

    when:
    writer.close()

    then:
    1 * monitor.onShutdown(true)

    cleanup:
    agent.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "unreachable agent test"() {
    setup:
    def minimalTrace = createMinimalTrace()
    def version = agentVersion

    def api = new DDAgentApi("localhost", 8192, null, 1000) {

      String detectEndpointAndBuildClient() {
        return version
      }

      DDAgentApi.Response sendSerializedTraces(int representativeCount, int traceCount, ByteBuffer dictionary, ByteBuffer traces) {
        // simulating a communication failure to a server
        return DDAgentApi.Response.failed(new IOException("comm error"))
      }
    }
    def writer = DDAgentWriter.builder().agentApi(api).monitor(monitor).build()

    when:
    writer.start()

    then:
    1 * monitor.onStart(writer.getDisruptorCapacity())

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    // if we know there's no agent, we'll drop the traces before serialising them
    // but we also know that there's nowhere to send health metrics to
    1 * monitor.onPublish(_)
    1 * monitor.onFlush(false)

    when:
    writer.close()

    then:
    1 * monitor.onShutdown(true)

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  @Retry(delay = 500)
  // if execution is too slow, the http client timeout may trigger.
  def "slow response test"() {
    def numWritten = 0
    def numFlushes = new AtomicInteger(0)
    def numPublished = new AtomicInteger(0)
    def numFailedPublish = new AtomicInteger(0)
    def numRequests = new AtomicInteger(0)
    def numFailedRequests = new AtomicInteger(0)

    def responseSemaphore = new Semaphore(1)

    setup:
    def version = agentVersion

    // Need to set-up a dummy agent for the final send callback to work
    def agent = httpServer {
      handlers {
        put(version) {
          responseSemaphore.acquire()
          try {
            response.status(200).send()
          } finally {
            responseSemaphore.release()
          }
        }
      }
    }

    // This test focuses just on failed publish, so not verifying every callback
    def monitor = Stub(Monitor) {
      onPublish(_) >> {
        numPublished.incrementAndGet()
      }
      onFailedPublish(_) >> {
        numFailedPublish.incrementAndGet()
      }
      onFlush(_) >> {
        numFlushes.incrementAndGet()
      }
      onSend(_, _, _) >> {
        numRequests.incrementAndGet()
      }
      onFailedPublish(_, _, _) >> {
        numFailedRequests.incrementAndGet()
      }
    }

    def writer = DDAgentWriter.builder().traceAgentPort(agent.address.port).monitor(monitor).traceBufferSize(bufferSize).build()
    writer.start()

    // gate responses
    responseSemaphore.acquire()

    when:
    // write a single trace and flush
    // with responseSemaphore held, the response is blocked but may still time out
    writer.write(minimalTrace)
    numWritten += 1

    // sanity check coordination mechanism of test
    // release to allow response to be generated
    responseSemaphore.release()
    writer.flush()

    // reacquire semaphore to stall further responses
    responseSemaphore.acquire()

    then:
    numFailedPublish.get() == 0
    numPublished.get() == numWritten
    numPublished.get() + numFailedPublish.get() == numWritten
    numFlushes.get() == 1

    when:
    // send many traces to fill the sender queue...
    //   loop until outstanding requests > finished requests
    while (writer.traceProcessingDisruptor.getDisruptorRemainingCapacity() > 0 || numFailedPublish.get() == 0) {
      writer.write(minimalTrace)
      numWritten += 1
    }

    then:
    numFailedPublish.get() > 0
    numPublished.get() + numFailedPublish.get() == numWritten

    when:

    // with both disruptor & queue full, should reject everything
    def expectedRejects = 100
    (1..expectedRejects).each {
      writer.write(minimalTrace)
      numWritten += 1
    }

    then:
    numPublished.get() + numFailedPublish.get() == numWritten

    cleanup:
    responseSemaphore.release()

    writer.close()
    agent.close()

    where:
    bufferSize = 16
    minimalTrace = createMinimalTrace()
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "multi threaded"() {
    def numPublished = new AtomicInteger(0)
    def numFailedPublish = new AtomicInteger(0)
    def numRepSent = new AtomicInteger(0)

    setup:
    def minimalTrace = createMinimalTrace()
    def version = agentVersion

    // Need to set-up a dummy agent for the final send callback to work
    def agent = httpServer {
      handlers {
        put(version) {
          response.status(200).send()
        }
      }
    }

    // This test focuses just on failed publish, so not verifying every callback
    def monitor = Stub(Monitor) {
      onPublish(_) >> {
        numPublished.incrementAndGet()
      }
      onFailedPublish(_) >> {
        numFailedPublish.incrementAndGet()
      }
      onSend(_, _, _) >> { repCount, sizeInBytes, response ->
        numRepSent.addAndGet(repCount)
      }
    }

    def writer = DDAgentWriter.builder().traceAgentPort(agent.address.port).monitor(monitor).build()
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

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "statsd success"() {
    def numTracesAccepted = 0
    def numRequests = 0
    def numResponses = 0

    setup:
    def minimalTrace = createMinimalTrace()
    def version = agentVersion

    // Need to set-up a dummy agent for the final send callback to work
    def agent = httpServer {
      handlers {
        put(version) {
          response.status(200).send()
        }
      }
    }

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

    def monitor = new Monitor(statsd)
    def writer = DDAgentWriter.builder().traceAgentPort(agent.address.port).monitor(monitor).build()
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

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "statsd comm failure"() {
    def numRequests = 0
    def numResponses = 0
    def numErrors = 0

    setup:
    def minimalTrace = createMinimalTrace()

    def api = apiWithVersion(agentVersion)
    api.sendSerializedTraces(_) >> DDAgentApi.Response.failed(new IOException("comm error"))

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

    def monitor = new Monitor(statsd)
    def writer = DDAgentWriter.builder().agentApi(api).monitor(monitor).build()
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

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  static int calculateSize(List<DDSpan> trace, Mapper<List<DDSpan>> mapper) {
    ByteBuffer buffer = ByteBuffer.allocate(1024)
    AtomicInteger size = new AtomicInteger()
    def packer = new Packer(new ByteBufferConsumer() {
      @Override
      void accept(int messageCount, ByteBuffer buffy) {
        size.set(buffy.limit() - buffy.position() - 1)
      }
    }, buffer)
    packer.format(trace, mapper)
    packer.flush()
    return size.get()
  }
}
