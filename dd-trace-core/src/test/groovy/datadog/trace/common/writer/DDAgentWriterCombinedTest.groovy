package datadog.trace.common.writer

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED

import datadog.communication.http.HttpUtils
import datadog.http.client.HttpUrl
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.ProcessTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.common.writer.ddagent.DDAgentApi
import datadog.communication.ddagent.DDAgentFeaturesDiscovery
import datadog.metrics.impl.MonitoringImpl
import datadog.metrics.api.statsd.StatsDClient
import datadog.trace.common.writer.ddagent.TraceMapperV0_4
import datadog.trace.common.writer.ddagent.TraceMapperV0_5
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.Mapper
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.core.monitor.TracerHealthMetrics
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.test.util.Flaky
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Phaser
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.common.writer.DDAgentWriter.BUFFER_SIZE
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE

@Timeout(10)
class DDAgentWriterCombinedTest extends DDCoreSpecification {

  def conditions = new PollingConditions(timeout: 5, initialDelay: 0, factor: 1.25)
  def monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
  def phaser = new Phaser()

  // Only used to create spans
  def dummyTracer = tracerBuilder().writer(new ListWriter()).build()

  def apiWithVersion(String version) {
    return Mock(DDAgentApi)
  }

  def setup() {
    // Register for two threads.
    phaser.register()
    phaser.register()
  }

  def cleanup() {
    dummyTracer?.close()
  }

  def "no interactions because of initial flush"() {
    setup:
    def api = apiWithVersion(agentVersion)
    def writer = DDAgentWriter.builder()
      .agentApi(api)
      .traceBufferSize(8)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(-1)
      .build()
    writer.start()

    when:
    writer.flush()

    then:
    0 * _

    cleanup:
    writer.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "test happy path"() {
    setup:
    def api = Mock(DDAgentApi)
    def discovery = Mock(DDAgentFeaturesDiscovery)
    def writer = DDAgentWriter.builder()
      .featureDiscovery(discovery)
      .agentApi(api)
      .traceBufferSize(1024)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(-1)
      .build()
    writer.start()
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when:
    writer.write(trace)
    writer.write(trace)
    writer.flush()

    then:
    2 * discovery.getTraceEndpoint() >> agentVersion
    1 * api.sendSerializedTraces({ it.traceCount() == 2 }) >> RemoteApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "test flood of traces"() {
    setup:
    def api = Mock(DDAgentApi)
    def discovery = Mock(DDAgentFeaturesDiscovery)
    def writer = DDAgentWriter.builder()
      .featureDiscovery(discovery)
      .agentApi(api)
      .traceBufferSize(bufferSize)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(-1)
      .build()
    writer.start()
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when:
    (1..traceCount).each {
      writer.write(trace)
    }
    writer.flush()

    then:
    2 * discovery.getTraceEndpoint() >> agentVersion
    1 * api.sendSerializedTraces({ it.traceCount() <= traceCount }) >> RemoteApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    bufferSize = 1024
    traceCount = 100 // Shouldn't trigger payload, but bigger than the disruptor size.
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "test flush by time"() {
    setup:
    def healthMetrics = Mock(HealthMetrics)
    def api = Mock(DDAgentApi)
    def discovery = Mock(DDAgentFeaturesDiscovery)
    def writer = DDAgentWriter.builder()
      .featureDiscovery(discovery)
      .agentApi(api)
      .healthMetrics(healthMetrics)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(1000)
      .build()
    writer.start()
    def span = dummyTracer.buildSpan("fakeOperation").start()
    def trace = (1..10).collect { span }

    when:
    (1..5).each {
      writer.write(trace)
    }
    phaser.awaitAdvanceInterruptibly(phaser.arriveAndDeregister())

    then:
    2 * discovery.getTraceEndpoint() >> agentVersion
    1 * healthMetrics.onSerialize(_)
    1 * api.sendSerializedTraces({ it.traceCount() == 5 }) >> RemoteApi.Response.success(200)
    _ * healthMetrics.onPublish(_, _)
    1 * healthMetrics.onSend(_, _, _) >> {
      phaser.arrive()
    }
    0 * _

    cleanup:
    writer.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  @Timeout(30)
  def "test default buffer size for #agentVersion"() {
    setup:
    // disable process tags since they are only written on the first span and it will break the trace size estimation
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false")
    ProcessTags.reset()
    def api = Mock(DDAgentApi)
    def discovery = Mock(DDAgentFeaturesDiscovery)
    def writer = DDAgentWriter.builder()
      .featureDiscovery(discovery)
      .agentApi(api)
      .traceBufferSize(BUFFER_SIZE)
      .prioritization(ENSURE_TRACE)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(-1)
      .build()
    writer.start()

    when:
    def mapper = agentVersion.equals("v0.5/traces") ? new TraceMapperV0_5() : new TraceMapperV0_4()
    int traceSize = calculateSize(minimalTrace, mapper)
    int maxedPayloadTraceCount = ((int) ((mapper.messageBufferSize()) / traceSize))
    (0..maxedPayloadTraceCount).each {
      writer.write(minimalTrace)
    }
    writer.flush()

    then:
    2 * discovery.getTraceEndpoint() >> agentVersion
    1 * api.sendSerializedTraces({ it.traceCount() == maxedPayloadTraceCount }) >> RemoteApi.Response.success(200)
    1 * api.sendSerializedTraces({ it.traceCount() == 1 }) >> RemoteApi.Response.success(200)
    0 * _

    cleanup:
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true")
    ProcessTags.reset()
    writer.close()

    where:
    minimalTrace = createMinimalTrace()
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "check that there are no interactions after close"() {
    setup:
    def healthMetrics = Mock(HealthMetrics)
    def api = Mock(DDAgentApi)
    def discovery = Mock(DDAgentFeaturesDiscovery)
    def writer = DDAgentWriter.builder()
      .featureDiscovery(discovery)
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
    1 * healthMetrics.onFailedPublish(_,_)
    1 * healthMetrics.onFlush(_)
    1 * healthMetrics.onShutdown(_)
    1 * healthMetrics.close()
    0 * _

    cleanup:
    writer.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def createMinimalContext() {
    def tracer = Stub(CoreTracer)
    def trace = Stub(PendingTrace)
    trace.mapServiceName(_) >> { String serviceName -> serviceName }
    trace.getTracer() >> tracer
    return new DDSpanContext(
      DDTraceId.ONE,
      1,
      DDSpanId.ZERO,
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
      trace,
      null,
      null,
      NoopPathwayContext.INSTANCE,
      false,
      PropagationTags.factory().empty())
  }

  def createMinimalTrace() {
    def context = createMinimalContext()
    def minimalSpan = new DDSpan("test", 0, context, null)
    context.getTraceCollector().getRootSpan() >> minimalSpan
    def minimalTrace = [minimalSpan]

    return minimalTrace
  }

  def "monitor happy path"() {
    setup:
    def healthMetrics = Mock(HealthMetrics)
    def minimalTrace = createMinimalTrace()

    // DQH -- need to set-up a dummy agent for the final send callback to work
    def agent = httpServer {
      handlers {
        put(agentVersion) {
          response.status(200).send()
        }
      }
    }
    def agentUrl = HttpUrl.from(agent.address)
    def client = HttpUtils.buildHttpClient(agentUrl, 1000)
    def discovery = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)
    def api = new DDAgentApi(client, agentUrl, discovery, monitoring, true)
    def writer = DDAgentWriter.builder()
      .featureDiscovery(discovery)
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
    1 * healthMetrics.onPublish(minimalTrace, _)
    1 * healthMetrics.onSerialize(_)
    1 * healthMetrics.onFlush(false)
    1 * healthMetrics.onSend(1, _, { response -> response.success() && response.status().present && response.status().asInt == 200 })

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
    def healthMetrics = Mock(HealthMetrics)
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
    def agentUrl = HttpUrl.from(agent.address)
    def client = HttpUtils.buildHttpClient(agentUrl, 1000)
    def discovery = new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true)
    def api = new DDAgentApi(client, agentUrl, discovery, monitoring, true)
    def writer = DDAgentWriter.builder()
      .featureDiscovery(discovery)
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
    1 * healthMetrics.onPublish(minimalTrace, _)
    1 * healthMetrics.onSerialize(_)
    1 * healthMetrics.onFlush(false)
    1 * healthMetrics.onFailedSend(1, _, { response -> !response.success() && response.status().present && response.status().asInt == 500 })

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
    def healthMetrics = Mock(HealthMetrics)
    def minimalTrace = createMinimalTrace()
    def version = agentVersion
    def discovery = Stub(DDAgentFeaturesDiscovery) {
      it.getTraceEndpoint() >> version
    }
    def api = Mock(DDAgentApi) {
      it.sendSerializedTraces(_) >> {
        // simulating a communication failure to a server
        return RemoteApi.Response.failed(new IOException("comm error"))
      }
    }

    def writer = DDAgentWriter.builder()
      .featureDiscovery(discovery)
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

  @Flaky("If execution is too slow, the http client timeout may trigger")
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
      onFailedPublish(_,_) >> {
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
      onFailedPublish(_,_) >> {
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
      assert numPublished.get() == totalTraces
      assert numRepSent.get() == totalTraces
    }

    cleanup:
    writer.close()
    agent.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "statsd success"() {
    def numTracesAccepted = new AtomicInteger(0)
    def numRequests = new AtomicInteger(0)
    def numResponses = new AtomicInteger(0)

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

    def healthMetrics = Stub(HealthMetrics)
    healthMetrics.onPublish(_, _) >> {
      numTracesAccepted.incrementAndGet()
    }
    healthMetrics.onSend(_, _, _) >> {
      numRequests.incrementAndGet()
      numResponses.incrementAndGet()
    }
    def writer = DDAgentWriter.builder()
      .agentHost(agent.address.host)
      .traceAgentV05Enabled(true)
      .traceAgentPort(agent.address.port)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics).build()
    writer.start()

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    numTracesAccepted.get() == 1
    numRequests.get() == 1
    numResponses.get() == 1

    cleanup:
    agent.close()
    writer.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  def "statsd comm failure"() {
    setup:
    def minimalTrace = createMinimalTrace()

    def api = apiWithVersion(agentVersion)
    api.sendSerializedTraces(_) >> RemoteApi.Response.failed(new IOException("comm error"))

    def latch = new CountDownLatch(2)
    def statsd = Mock(StatsDClient)
    def healthMetrics = new TracerHealthMetrics(statsd, 100, TimeUnit.MILLISECONDS)
    def writer = DDAgentWriter.builder()
      .traceAgentV05Enabled(true)
      .agentApi(api).monitoring(monitoring)
      .healthMetrics(healthMetrics).build()
    healthMetrics.start()
    writer.start()

    when:
    writer.write(minimalTrace)
    writer.flush()
    latch.await(10, TimeUnit.SECONDS)

    then:
    1 * statsd.count("api.requests.total", 1, _) >> {
      latch.countDown()
    }
    0 * statsd.incrementCounter("api.responses.total", _)
    1 * statsd.count("api.errors.total", 1, _) >> {
      latch.countDown()
    }

    cleanup:
    writer.close()
    healthMetrics.close()

    where:
    agentVersion << ["v0.3/traces", "v0.4/traces", "v0.5/traces"]
  }

  static int calculateSize(List<DDSpan> trace, Mapper<List<DDSpan>> mapper) {
    AtomicInteger size = new AtomicInteger()
    def packer = new MsgPackWriter(new FlushingBuffer(1024, new ByteBufferConsumer() {
        @Override
        void accept(int messageCount, ByteBuffer buffer) {
          size.set(buffer.limit() - buffer.position())
        }
      }))
    packer.format(trace, mapper)
    packer.flush()
    return size.get()
  }
}
