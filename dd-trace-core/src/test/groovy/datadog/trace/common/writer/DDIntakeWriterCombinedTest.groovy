package datadog.trace.common.writer

import datadog.communication.http.OkHttpUtils
import datadog.communication.serialization.ByteBufferConsumer
import datadog.communication.serialization.FlushingBuffer
import datadog.communication.serialization.msgpack.MsgPackWriter
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTraceId
import datadog.trace.api.StatsDClient
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags
import datadog.trace.api.intake.TrackType
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.api.datastreams.NoopPathwayContext
import datadog.trace.common.writer.ddintake.DDIntakeApi
import datadog.trace.common.writer.ddintake.DDIntakeMapperDiscovery
import datadog.trace.core.CoreTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.core.monitor.HealthMetrics
import datadog.trace.core.monitor.MonitoringImpl
import datadog.trace.core.monitor.TracerHealthMetrics
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.test.DDCoreSpecification
import datadog.trace.test.util.Flaky
import okhttp3.HttpUrl
import spock.lang.Shared
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Phaser
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.common.writer.DDIntakeWriter.BUFFER_SIZE
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE

@Timeout(10)
class DDIntakeWriterCombinedTest extends DDCoreSpecification {

  @Shared
  def wellKnownTags = new CiVisibilityWellKnownTags(
  "my-runtime-id", "my-env", "my-language",
  "my-runtime-name", "my-runtime-version", "my-runtime-vendor",
  "my-os-arch", "my-os-platform", "my-os-version", "false")

  def conditions = new PollingConditions(timeout: 5, initialDelay: 0, factor: 1.25)
  def monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS)
  def phaser = new Phaser()

  // Only used to create spans
  def dummyTracer = tracerBuilder().writer(new ListWriter()).build()

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
    def api = Mock(DDIntakeApi)
    def writer = DDIntakeWriter.builder()
      .addTrack(TrackType.NOOP, api)
      .traceBufferSize(8)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(-1)
      .alwaysFlush(false)
      .build()
    writer.start()

    when:
    writer.flush()

    then:
    0 * _

    cleanup:
    writer.close()
  }

  def "test happy path"() {
    setup:
    def api = Mock(DDIntakeApi)
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .traceBufferSize(1024)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(-1)
      .alwaysFlush(false)
      .build()
    writer.start()
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when:
    writer.write(trace)
    writer.write(trace)
    writer.flush()

    then:
    1 * api.sendSerializedTraces({ it.traceCount() == 2 }) >> RemoteApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    trackType << [TrackType.CITESTCYCLE]
  }

  def "test flood of traces"() {
    setup:
    def api = Mock(DDIntakeApi)
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .traceBufferSize(1024)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(-1)
      .alwaysFlush(false)
      .build()
    writer.start()
    def trace = [dummyTracer.buildSpan("fakeOperation").start()]

    when:
    (1..traceCount).each {
      writer.write(trace)
    }
    writer.flush()

    then:
    1 * api.sendSerializedTraces({ it.traceCount() <= traceCount }) >> RemoteApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    bufferSize = 1024
    traceCount = 100 // Shouldn't trigger payload, but bigger than the disruptor size.
    trackType << [TrackType.CITESTCYCLE]
  }

  def "test flush by time"() {
    setup:
    def healthMetrics = Mock(HealthMetrics)
    def api = Mock(DDIntakeApi)
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .healthMetrics(healthMetrics)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(1000)
      .alwaysFlush(false)
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
    trackType << [TrackType.CITESTCYCLE]
  }

  @Timeout(30)
  def "test default buffer size for #trackType"() {
    setup:
    def api = Mock(DDIntakeApi)
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .wellKnownTags(wellKnownTags)
      .traceBufferSize(BUFFER_SIZE)
      .prioritization(ENSURE_TRACE)
      .monitoring(monitoring)
      .flushIntervalMilliseconds(-1)
      .alwaysFlush(false)
      .build()
    writer.start()

    when:
    def discovery = new DDIntakeMapperDiscovery(trackType, wellKnownTags, false)
    discovery.discover()
    def mapper = (RemoteMapper) discovery.mapper
    def traceSize = calculateSize(minimalTrace, mapper)
    int maxedPayloadTraceCount = ((int) ((mapper.messageBufferSize()) / traceSize))
    (0..maxedPayloadTraceCount).each {
      writer.write(minimalTrace)
    }
    writer.flush()

    then:
    1 * api.sendSerializedTraces({ it.traceCount() == maxedPayloadTraceCount }) >> RemoteApi.Response.success(200)
    1 * api.sendSerializedTraces({ it.traceCount() == 1 }) >> RemoteApi.Response.success(200)
    0 * _

    cleanup:
    writer.close()

    where:
    minimalTrace = createMinimalTrace()
    trackType << [TrackType.CITESTCYCLE]
  }

  def "check that there are no interactions after close"() {
    setup:
    def api = Mock(DDIntakeApi)
    def healthMetrics = Mock(HealthMetrics)
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .healthMetrics(healthMetrics)
      .monitoring(monitoring)
      .alwaysFlush(false)
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
    trackType << [TrackType.CITESTCYCLE]
  }

  def "monitor happy path"() {
    setup:
    def healthMetrics = Mock(HealthMetrics)
    def minimalTrace = createMinimalTrace()
    def path = buildIntakePath(trackType, apiVersion)
    def intake = httpServer {
      handlers {
        post(path) {
          response.status(200).send()
        }
      }
    }
    def hostUrl = HttpUrl.get(intake.address)
    def client = OkHttpUtils.buildHttpClient(hostUrl, 1000)
    def api = DDIntakeApi.builder()
      .hostUrl(hostUrl)
      .httpClient(client)
      .apiKey("my-api-key")
      .trackType(trackType)
      .build()
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .healthMetrics(healthMetrics)
      .monitoring(monitoring)
      .alwaysFlush(false)
      .build()

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
    intake.close()

    where:
    trackType | apiVersion
    TrackType.CITESTCYCLE | "v2"
  }

  def "monitor intake returns error"() {
    setup:
    def healthMetrics = Mock(HealthMetrics)
    def minimalTrace = createMinimalTrace()

    def path = buildIntakePath(trackType, apiVersion)
    def intake = httpServer {
      handlers {
        post(path) {
          response.status(500).send()
        }
      }
    }
    def hostUrl = HttpUrl.get(intake.address)
    def client = OkHttpUtils.buildHttpClient(hostUrl, 1000)
    def api = DDIntakeApi.builder()
      .hostUrl(hostUrl)
      .httpClient(client)
      .apiKey("my-api-key")
      .trackType(trackType)
      .build()
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .healthMetrics(healthMetrics)
      .monitoring(monitoring)
      .alwaysFlush(false)
      .build()

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
    intake.close()

    where:
    trackType | apiVersion
    TrackType.CITESTCYCLE | "v2"
  }

  def "unreachable intake test"() {
    setup:
    def healthMetrics = Mock(HealthMetrics)
    def minimalTrace = createMinimalTrace()

    def api = Mock(DDIntakeApi) {
      it.sendSerializedTraces(_) >> {
        // simulating a communication failure to a server
        return RemoteApi.Response.failed(new IOException("comm error"))
      }
    }

    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics)
      .alwaysFlush(false)
      .build()

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
    trackType << [TrackType.CITESTCYCLE]
  }

  @Flaky
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
    def path = buildIntakePath(trackType, apiVersion)
    def intake = httpServer {
      handlers {
        post(path) {
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

    def hostUrl = HttpUrl.get(intake.address)
    def client = OkHttpUtils.buildHttpClient(hostUrl, 1000)
    def api = DDIntakeApi.builder()
      .hostUrl(hostUrl)
      .httpClient(client)
      .apiKey("my-api-key")
      .trackType(trackType)
      .build()
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .healthMetrics(healthMetrics)
      .traceBufferSize(bufferSize)
      .alwaysFlush(false)
      .build()
    writer.start()

    // gate responses
    responseSemaphore.acquire()

    // sanity check coordination mechanism of test
    // release to allow response to be generated
    responseSemaphore.release()
    writer.flush()

    // reacquire semaphore to stall further responses
    responseSemaphore.acquire()

    when:
    // write a single trace and flush
    // with responseSemaphore held, the response is blocked but may still time out
    writer.write(minimalTrace)
    numWritten += 1

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
    intake.close()

    where:
    bufferSize = 16
    minimalTrace = createMinimalTrace()
    trackType | apiVersion
    TrackType.CITESTCYCLE | "v2"
  }

  def "multi threaded"() {
    def numPublished = new AtomicInteger(0)
    def numFailedPublish = new AtomicInteger(0)
    def numRepSent = new AtomicInteger(0)

    setup:
    def minimalTrace = createMinimalTrace()
    def path = buildIntakePath(trackType, apiVersion)
    def intake = httpServer {
      handlers {
        post(path) {
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

    def hostUrl = HttpUrl.get(intake.address)
    def client = OkHttpUtils.buildHttpClient(hostUrl, 1000)
    def api = DDIntakeApi.builder()
      .hostUrl(hostUrl)
      .httpClient(client)
      .apiKey("my-api-key")
      .trackType(trackType)
      .build()
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics)
      .alwaysFlush(false)
      .build()
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
    intake.close()

    where:
    trackType | apiVersion
    TrackType.CITESTCYCLE | "v2"
  }

  def "statsd success"() {
    def numTracesAccepted = new AtomicInteger(0)
    def numRequests = new AtomicInteger(0)
    def numResponses = new AtomicInteger(0)

    setup:
    def minimalTrace = createMinimalTrace()
    def path = buildIntakePath(trackType, apiVersion)
    def intake = httpServer {
      handlers {
        post(path) {
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
    def hostUrl = HttpUrl.get(intake.address)
    def client = OkHttpUtils.buildHttpClient(hostUrl, 1000)
    def api = DDIntakeApi.builder()
      .hostUrl(hostUrl)
      .httpClient(client)
      .apiKey("my-api-key")
      .trackType(trackType)
      .build()
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics)
      .alwaysFlush(false)
      .build()
    writer.start()

    when:
    writer.write(minimalTrace)
    writer.flush()

    then:
    numTracesAccepted.get() == 1
    numRequests.get() == 1
    numResponses.get() == 1

    cleanup:
    intake.close()
    writer.close()

    where:
    trackType | apiVersion
    TrackType.CITESTCYCLE | "v2"
  }

  def "statsd comm failure"() {
    setup:
    def minimalTrace = createMinimalTrace()

    def api = Mock(DDIntakeApi)
    api.sendSerializedTraces(_) >> RemoteApi.Response.failed(new IOException("comm error"))

    def latch = new CountDownLatch(2)
    def statsd = Mock(StatsDClient)
    def healthMetrics = new TracerHealthMetrics(statsd, 100, TimeUnit.MILLISECONDS)
    def writer = DDIntakeWriter.builder()
      .addTrack(trackType, api)
      .monitoring(monitoring)
      .healthMetrics(healthMetrics)
      .alwaysFlush(false)
      .build()
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
    trackType | apiVersion
    TrackType.CITESTCYCLE | "v2"
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

  static int calculateSize(List<DDSpan> trace, RemoteMapper mapper) {
    AtomicInteger size = new AtomicInteger()
    def packer = new MsgPackWriter(new FlushingBuffer(mapper.messageBufferSize(), new ByteBufferConsumer() {
        @Override
        void accept(int messageCount, ByteBuffer buffer) {
          size.set(buffer.limit() - buffer.position())
        }
      }))
    packer.format(trace, mapper)
    packer.flush()
    return size.get()
  }

  def buildIntakePath(TrackType trackType, String apiVersion) {
    return String.format("/api/%s/%s", apiVersion, trackType.name().toLowerCase())
  }
}
