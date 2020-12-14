package datadog.trace.common.writer

import com.timgroup.statsd.NoOpStatsDClient
import com.timgroup.statsd.StatsDClient
import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.trace.common.writer.ddagent.Payload
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.Monitoring
import datadog.trace.core.serialization.ByteBufferConsumer
import datadog.trace.core.serialization.Mapper
import datadog.trace.core.serialization.msgpack.MsgPackWriter
import datadog.trace.test.util.DDSpecification
import spock.lang.Retry
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

import java.nio.ByteBuffer
import java.util.concurrent.Phaser
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.common.writer.DDAgentWriter.BUFFER_SIZE
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE
import static datadog.trace.core.SpanFactory.newSpanOf

@Timeout(10)
class DDAgentWriterCombinedTest extends DDSpecification {

  def conditions = new PollingConditions(timeout: 5, initialDelay: 0, factor: 1.25)
  def monitoring = new Monitoring(new NoOpStatsDClient(), 1, TimeUnit.SECONDS)
  def phaser = new Phaser()

  def apiWithVersion(String version) {
    def api = Mock(DDAgentApi)
    api.detectEndpoint() >> version
    api.selectTraceMapper() >> { callRealMethod() }
    return api
  }
  def healthMetrics = Mock(HealthMetrics)

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
      .traceBufferSize(8)
      .monitoring(monitoring)
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
      .traceBufferSize(1024)
      .monitoring(monitoring)
      .flushFrequencySeconds(-1)
      .build()
    writer.start()

    when:
    writer.write(trace)
    writer.write(trace)
    writer.flush()

    then:
    1 * api.detectEndpoint() >> agentVersion
    1 * api.selectTraceMapper() >> { callRealMethod() }
    1 * api.sendSerializedTraces({ it.traceCount() == 2 }) >> DDAgentApi.Response.success(200)
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
      .traceBufferSize(bufferSize)
      .monitoring(monitoring)
      .flushFrequencySeconds(-1)
      .build()
    writer.start()

    when:
    (1..traceCount).each {
      writer.write(trace)
    }
    writer.flush()

    then:
    1 * api.detectEndpoint() >> agentVersion
    1 * api.selectTraceMapper() >> { callRealMethod() }
    1 * api.sendSerializedTraces({ it.traceCount() <= traceCount }) >> DDAgentApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    trace = [newSpanOf(0, "fixed-thread-name")]
    bufferSize = 1024
    traceCount = 100 // Shouldn't trigger payload, but bigger than the disruptor size.
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "test flush by time"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .healthMetrics(healthMetrics)
      .monitoring(monitoring)
      .flushFrequencySeconds(1)
      .build()
    writer.start()

    when:
    (1..5).each {
      writer.write(trace)
    }
    phaser.awaitAdvanceInterruptibly(phaser.arriveAndDeregister())

    then:
    1 * api.detectEndpoint() >> agentVersion
    1 * api.selectTraceMapper() >> { callRealMethod() }
    1 * healthMetrics.onSerialize(_)
    1 * api.sendSerializedTraces({ it.traceCount() == 5 }) >> DDAgentApi.Response.success(200)
    _ * healthMetrics.onPublish(_, _)
    1 * healthMetrics.onSend(_, _, _) >> {
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

  @Timeout(30)
  def "test default buffer size for #agentVersion"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .traceBufferSize(BUFFER_SIZE)
      .prioritization(ENSURE_TRACE)
      .monitoring(monitoring)
      .flushFrequencySeconds(-1)
      .build()
    writer.start()

    when:
    def mapper = agentVersion.equals("v0.5/traces") ? new TraceMapperV0_5() : new TraceMapperV0_4()
    int traceSize = calculateSize(minimalTrace, mapper)
    int maxedPayloadTraceCount = ((int) ((mapper.messageBufferSize() - 5) / traceSize))
    (0..maxedPayloadTraceCount).each {
      writer.write(minimalTrace)
    }
    writer.flush()

    then:
    1 * api.detectEndpoint() >> agentVersion
    1 * api.selectTraceMapper() >> { callRealMethod() }
    1 * api.sendSerializedTraces({ it.traceCount() == maxedPayloadTraceCount }) >> DDAgentApi.Response.success(200)
    1 * api.sendSerializedTraces({ it.traceCount() == 1 }) >> DDAgentApi.Response.success(200)
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
      "",
      PrioritySampling.UNSET,
      "",
      [:],
      false,
      "",
      0,
      Mock(PendingTrace),
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
      .healthMetrics(healthMetrics)
      .monitoring(monitoring)
      .build()
    writer.start()

    when:
    writer.close()
    writer.write([])
    writer.flush()

    then:
    // this will be checked during flushing
    1 * healthMetrics.onFailedPublish(_)
    1 * healthMetrics.onFlush(_)
    1 * healthMetrics.onShutdown(_)
    0 * _

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
      "",
      PrioritySampling.UNSET,
      "",
      [:],
      false,
      "",
      0,
      Mock(PendingTrace),
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
    def writer = DDAgentWriter.builder()
      .traceAgentV05Enabled(true)
      .traceAgentPort(agent.address.port)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics).build()

    when:
    writer.start()

    then:
    1 * healthMetrics.onStart(writer.getCapacity())

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    1 * healthMetrics.onPublish(minimalTrace, _)
    1 * healthMetrics.onSerialize(_)
    1 * healthMetrics.onFlush(false)
    1 * healthMetrics.onSend(1, _, { response -> response.success() && response.status() == 200 })

    when:
    writer.close()

    then:
    1 * healthMetrics.onShutdown(true)

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
    def writer = DDAgentWriter.builder()
      .traceAgentV05Enabled(true)
      .traceAgentPort(agent.address.port)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics).build()

    when:
    writer.start()

    then:
    1 * healthMetrics.onStart(writer.getCapacity())

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    1 * healthMetrics.onPublish(minimalTrace, _)
    1 * healthMetrics.onSerialize(_)
    1 * healthMetrics.onFlush(false)
    1 * healthMetrics.onFailedSend(1, _, { response -> !response.success() && response.status() == 500 })

    when:
    writer.close()

    then:
    1 * healthMetrics.onShutdown(true)

    cleanup:
    agent.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "unreachable agent test"() {
    setup:
    def minimalTrace = createMinimalTrace()
    def version = agentVersion

    def api = new DDAgentApi("http://localhost:8192", null, 1000, monitoring) {

      String detectEndpoint() {
        return version
      }

      DDAgentApi.Response sendSerializedTraces(Payload payload) {
        // simulating a communication failure to a server
        return DDAgentApi.Response.failed(new IOException("comm error"))
      }
    }
    def writer = DDAgentWriter.builder()
      .traceAgentV05Enabled(true)
      .agentApi(api)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics).build()

    when:
    writer.start()

    then:
    1 * healthMetrics.onStart(writer.getCapacity())

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    // if we know there's no agent, we'll drop the traces before serialising them
    // but we also know that there's nowhere to send health metrics to
    1 * healthMetrics.onPublish(_, _)
    1 * healthMetrics.onFlush(false)

    when:
    writer.close()

    then:
    1 * healthMetrics.onShutdown(true)

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
    def healthMetrics = Stub(HealthMetrics) {
      onPublish(_, _) >> {
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
      onFailedSend(_, _, _) >> {
        numFailedRequests.incrementAndGet()
      }
    }

    def writer = DDAgentWriter.builder()
      .traceAgentV05Enabled(true)
      .traceAgentPort(agent.address.port)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics)
      .traceBufferSize(bufferSize).build()
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
    while (writer.traceProcessingWorker.getRemainingCapacity() > 0 || numFailedPublish.get() == 0) {
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
    def healthMetrics = Stub(HealthMetrics) {
      onPublish(_, _) >> {
        numPublished.incrementAndGet()
      }
      onFailedPublish(_) >> {
        numFailedPublish.incrementAndGet()
      }
      onSend(_, _, _) >> { repCount, sizeInBytes, response ->
        numRepSent.addAndGet(repCount)
      }
    }

    def writer = DDAgentWriter.builder()
      .traceAgentV05Enabled(true)
      .traceAgentPort(agent.address.port)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics).build()
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
    conditions.eventually {
      def totalTraces = 100 + 100
      numPublished.get() == totalTraces
      numRepSent.get() == totalTraces
    }

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
    statsd.incrementCounter("queue.enqueued.traces", _) >> { stat ->
      numTracesAccepted += 1
    }
    statsd.incrementCounter("api.requests.total") >> { stat ->
      numRequests += 1
    }
    statsd.incrementCounter("api.responses.total", _) >> { stat, tags ->
      numResponses += 1
    }

    def healthMetrics = new HealthMetrics(statsd)
    def writer = DDAgentWriter.builder()
      .traceAgentV05Enabled(true)
      .traceAgentPort(agent.address.port)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics).build()
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
    statsd.incrementCounter("api.requests.total") >> { stat ->
      numRequests += 1
    }
    statsd.incrementCounter("api.responses.total", _) >> { stat, tags ->
      numResponses += 1
    }
    statsd.incrementCounter("api.errors.total", _) >> { stat ->
      numErrors += 1
    }

    def healthMetrics = new HealthMetrics(statsd)
    def writer = DDAgentWriter.builder()
      .traceAgentV05Enabled(true)
      .agentApi(api).monitoring(monitoring)
      .healthMetrics(healthMetrics).build()
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
    def packer = new MsgPackWriter(new ByteBufferConsumer() {
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
