package datadog.trace.common.writer;

import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.communication.http.OkHttpUtils;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.common.writer.ddintake.DDIntakeApi;
import datadog.trace.common.writer.ddintake.DDIntakeMapperDiscovery;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.TracerHealthMetrics;
import datadog.trace.test.util.Flaky;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DDIntakeWriterCombinedTest extends DDCoreJavaSpecification {

  private static final CiVisibilityWellKnownTags wellKnownTags =
      new CiVisibilityWellKnownTags(
          "my-runtime-id",
          "my-env",
          "my-language",
          "my-runtime-name",
          "my-runtime-version",
          "my-runtime-vendor",
          "my-os-arch",
          "my-os-platform",
          "my-os-version",
          "false");

  MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
  Phaser phaser = new Phaser();

  // Only used to create spans
  datadog.trace.core.CoreTracer dummyTracer;

  @BeforeEach
  void setup() {
    // Register for two threads.
    phaser.register();
    phaser.register();
    dummyTracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void cleanup() {
    if (dummyTracer != null) {
      dummyTracer.close();
    }
  }

  List<DDSpan> createMinimalTrace() {
    // Use buildSpan from DDCoreJavaSpecification to create a real DDSpan with minimal fields
    DDSpan span = buildSpan(0L, "", Collections.emptyMap());
    return Collections.singletonList(span);
  }

  @Test
  void noInteractionsBecauseOfInitialFlush() {
    DDIntakeApi api = mock(DDIntakeApi.class);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.NOOP, api)
            .traceBufferSize(8)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .alwaysFlush(false)
            .build();
    writer.start();
    // Clear setup-time interactions (e.g. isCompressionEnabled() called during build())
    clearInvocations(api);

    writer.flush();

    // then: 0 * _ (no interactions at all on mocked api)
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @Test
  void testHappyPath() {
    TrackType trackType = TrackType.CITESTCYCLE;
    DDIntakeApi api = mock(DDIntakeApi.class);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .traceBufferSize(1024)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .alwaysFlush(false)
            .build();
    writer.start();
    // Clear setup-time interactions (e.g. isCompressionEnabled() called during build())
    clearInvocations(api);
    DDSpan span = (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start();
    List<DDSpan> trace = Collections.singletonList(span);

    doAnswer(invocation -> RemoteApi.Response.success(200))
        .when(api)
        .sendSerializedTraces(argThat(payload -> payload.traceCount() == 2));

    writer.write(trace);
    writer.write(trace);
    writer.flush();

    verify(api, times(1)).sendSerializedTraces(argThat(payload -> payload.traceCount() == 2));
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @Test
  void testFloodOfTraces() {
    // bufferSize = 1024; traceCount = 100 (shouldn't trigger payload, but bigger than disruptor
    // size)
    int traceCount = 100;
    TrackType trackType = TrackType.CITESTCYCLE;
    DDIntakeApi api = mock(DDIntakeApi.class);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .traceBufferSize(1024)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .alwaysFlush(false)
            .build();
    writer.start();
    // Clear setup-time interactions (e.g. isCompressionEnabled() called during build())
    clearInvocations(api);
    DDSpan span = (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start();
    List<DDSpan> trace = Collections.singletonList(span);

    doAnswer(invocation -> RemoteApi.Response.success(200))
        .when(api)
        .sendSerializedTraces(argThat(payload -> payload.traceCount() <= traceCount));

    for (int i = 1; i <= traceCount; i++) {
      writer.write(trace);
    }
    writer.flush();

    verify(api, times(1))
        .sendSerializedTraces(argThat(payload -> payload.traceCount() <= traceCount));
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @Test
  void testFlushByTime() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDIntakeApi api = mock(DDIntakeApi.class);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .healthMetrics(healthMetrics)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(1000)
            .alwaysFlush(false)
            .build();
    writer.start();
    // Clear setup-time interactions (e.g. isCompressionEnabled() called during build(), onStart
    // from start())
    clearInvocations(api, healthMetrics);
    DDSpan span = (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start();
    List<DDSpan> trace = Collections.nCopies(10, span);

    doAnswer(invocation -> RemoteApi.Response.success(200))
        .when(api)
        .sendSerializedTraces(argThat(payload -> payload.traceCount() == 5));

    // stub onSend to arrive at phaser
    doAnswer(
            invocation -> {
              phaser.arrive();
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());

    for (int i = 1; i <= 5; i++) {
      writer.write(trace);
    }
    phaser.awaitAdvanceInterruptibly(phaser.arriveAndDeregister());

    verify(healthMetrics, times(1)).onSerialize(anyInt());
    verify(api, times(1)).sendSerializedTraces(argThat(payload -> payload.traceCount() == 5));
    // _ * healthMetrics.onPublish(_, _) means any number, so don't verify count
    verify(healthMetrics).onSend(anyInt(), anyInt(), any());
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  @Test
  void testDefaultBufferSizeForCitestcycle() {
    TrackType trackType = TrackType.CITESTCYCLE;
    List<DDSpan> minimalTrace = createMinimalTrace();
    DDIntakeApi api = mock(DDIntakeApi.class);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .wellKnownTags(wellKnownTags)
            .traceBufferSize(1024)
            .prioritization(ENSURE_TRACE)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .alwaysFlush(false)
            .build();
    writer.start();
    // Clear setup-time interactions (e.g. isCompressionEnabled() called during build())
    clearInvocations(api);

    DDIntakeMapperDiscovery discovery =
        new DDIntakeMapperDiscovery(trackType, wellKnownTags, false);
    discovery.discover();
    RemoteMapper mapper = discovery.getMapper();
    int traceSize = calculateSize(minimalTrace, mapper);
    int maxedPayloadTraceCount = (mapper.messageBufferSize() / traceSize);

    doAnswer(invocation -> RemoteApi.Response.success(200))
        .when(api)
        .sendSerializedTraces(
            argThat(payload -> payload != null && payload.traceCount() == maxedPayloadTraceCount));
    doAnswer(invocation -> RemoteApi.Response.success(200))
        .when(api)
        .sendSerializedTraces(argThat(payload -> payload != null && payload.traceCount() == 1));

    for (int i = 0; i <= maxedPayloadTraceCount; i++) {
      writer.write(minimalTrace);
    }
    writer.flush();

    verify(api, times(1))
        .sendSerializedTraces(
            argThat(payload -> payload != null && payload.traceCount() == maxedPayloadTraceCount));
    verify(api, times(1))
        .sendSerializedTraces(argThat(payload -> payload != null && payload.traceCount() == 1));
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @Test
  void checkThatThereAreNoInteractionsAfterClose() {
    TrackType trackType = TrackType.CITESTCYCLE;
    DDIntakeApi api = mock(DDIntakeApi.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .healthMetrics(healthMetrics)
            .monitoring(monitoring)
            .alwaysFlush(false)
            .build();
    writer.start();
    // Clear setup-time interactions (e.g. isCompressionEnabled() called during build(), onStart
    // from start())
    clearInvocations(api, healthMetrics);

    writer.close();
    writer.write(Collections.emptyList());
    writer.flush();

    // then: this will be checked during flushing
    verify(healthMetrics, times(1)).onFailedPublish(anyInt(), anyInt());
    verify(healthMetrics, times(1)).onFlush(any(Boolean.class));
    verify(healthMetrics, times(1)).onShutdown(any(Boolean.class));
    verify(healthMetrics, times(1)).close();
    verifyNoMoreInteractions(healthMetrics, api);

    writer.close();
  }

  @Test
  void monitorHappyPath() {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();
    String path = buildIntakePath(trackType, apiVersion);

    JavaTestHttpServer intake =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(h -> h.post(path, api -> api.getResponse().status(200).send())));
    try {
      HttpUrl hostUrl = HttpUrl.get(intake.getAddress());
      OkHttpClient httpClient = OkHttpUtils.buildHttpClient(hostUrl, 1000);
      DDIntakeApi api =
          DDIntakeApi.builder()
              .hostUrl(hostUrl)
              .httpClient(httpClient)
              .apiKey("my-api-key")
              .trackType(trackType)
              .build();
      DDIntakeWriter writer =
          DDIntakeWriter.builder()
              .addTrack(trackType, api)
              .healthMetrics(healthMetrics)
              .monitoring(monitoring)
              .alwaysFlush(false)
              .build();

      // start
      writer.start();

      verify(healthMetrics, times(1)).onStart((int) writer.getCapacity());

      // write and flush
      writer.write(minimalTrace);
      writer.flush();

      verify(healthMetrics, times(1)).onPublish(any(), anyInt());
      verify(healthMetrics, times(1)).onSerialize(anyInt());
      verify(healthMetrics, times(1)).onFlush(false);
      verify(healthMetrics, times(1))
          .onSend(
              anyInt(),
              anyInt(),
              argThat(
                  response ->
                      response.success()
                          && response.status().isPresent()
                          && response.status().getAsInt() == 200));

      writer.close();

      verify(healthMetrics, times(1)).onShutdown(true);
    } finally {
      intake.close();
    }
  }

  @Test
  void monitorIntakeReturnsError() {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();
    String path = buildIntakePath(trackType, apiVersion);

    JavaTestHttpServer intake =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(h -> h.post(path, api -> api.getResponse().status(500).send())));
    try {
      HttpUrl hostUrl = HttpUrl.get(intake.getAddress());
      okhttp3.OkHttpClient httpClient = OkHttpUtils.buildHttpClient(hostUrl, 1000);
      DDIntakeApi api =
          DDIntakeApi.builder()
              .hostUrl(hostUrl)
              .httpClient(httpClient)
              .apiKey("my-api-key")
              .trackType(trackType)
              .build();
      DDIntakeWriter writer =
          DDIntakeWriter.builder()
              .addTrack(trackType, api)
              .healthMetrics(healthMetrics)
              .monitoring(monitoring)
              .alwaysFlush(false)
              .build();

      // start
      writer.start();

      verify(healthMetrics, times(1)).onStart((int) writer.getCapacity());

      // write and flush
      writer.write(minimalTrace);
      writer.flush();

      verify(healthMetrics, times(1)).onPublish(any(), anyInt());
      verify(healthMetrics, times(1)).onSerialize(anyInt());
      verify(healthMetrics, times(1)).onFlush(false);
      verify(healthMetrics, times(1))
          .onFailedSend(
              anyInt(),
              anyInt(),
              argThat(
                  response ->
                      !response.success()
                          && response.status().isPresent()
                          && response.status().getAsInt() == 500));

      writer.close();

      verify(healthMetrics, times(1)).onShutdown(true);
    } finally {
      intake.close();
    }
  }

  @Test
  void unreachableIntakeTest() {
    TrackType trackType = TrackType.CITESTCYCLE;
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();
    DDIntakeApi api = mock(DDIntakeApi.class);
    // simulating a communication failure to a server
    doAnswer(invocation -> RemoteApi.Response.failed(new IOException("comm error")))
        .when(api)
        .sendSerializedTraces(any());

    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .alwaysFlush(false)
            .build();

    // start
    writer.start();

    verify(healthMetrics, times(1)).onStart((int) writer.getCapacity());

    // write and flush
    writer.write(minimalTrace);
    writer.flush();

    // then: if we know there's no agent, we'll drop the traces before serialising them
    // but we also know that there's nowhere to send health metrics to
    verify(healthMetrics, times(1)).onPublish(any(), anyInt());
    verify(healthMetrics, times(1)).onFlush(false);

    writer.close();

    verify(healthMetrics, times(1)).onShutdown(true);
  }

  @Flaky("If execution is too slow, the http client timeout may trigger")
  @Test
  void slowResponseTest() throws Exception {
    int numWritten = 0;
    AtomicInteger numFlushes = new AtomicInteger(0);
    AtomicInteger numPublished = new AtomicInteger(0);
    AtomicInteger numFailedPublish = new AtomicInteger(0);
    AtomicInteger numRequests = new AtomicInteger(0);
    AtomicInteger numFailedRequests = new AtomicInteger(0);

    Semaphore responseSemaphore = new Semaphore(1);

    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    int bufferSize = 16;
    List<DDSpan> minimalTrace = createMinimalTrace();
    String path = buildIntakePath(trackType, apiVersion);

    JavaTestHttpServer intake =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    h ->
                        h.post(
                            path,
                            api -> {
                              responseSemaphore.acquire();
                              try {
                                api.getResponse().status(200).send();
                              } finally {
                                responseSemaphore.release();
                              }
                            })));

    // This test focuses just on failed publish, so not verifying every callback
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    doAnswer(
            invocation -> {
              numPublished.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onPublish(any(), anyInt());
    doAnswer(
            invocation -> {
              numFailedPublish.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFailedPublish(anyInt(), anyInt());
    doAnswer(
            invocation -> {
              numFlushes.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFlush(any(Boolean.class));
    doAnswer(
            invocation -> {
              numRequests.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());
    doAnswer(
            invocation -> {
              numFailedRequests.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFailedSend(anyInt(), anyInt(), any());

    HttpUrl hostUrl = HttpUrl.get(intake.getAddress());
    okhttp3.OkHttpClient httpClient = OkHttpUtils.buildHttpClient(hostUrl, 1000);
    DDIntakeApi api =
        DDIntakeApi.builder()
            .hostUrl(hostUrl)
            .httpClient(httpClient)
            .apiKey("my-api-key")
            .trackType(trackType)
            .build();
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .healthMetrics(healthMetrics)
            .traceBufferSize(bufferSize)
            .alwaysFlush(false)
            .build();
    writer.start();

    // gate responses
    responseSemaphore.acquire();

    try {
      // sanity check coordination mechanism of test
      // release to allow response to be generated
      responseSemaphore.release();
      writer.flush();

      // reacquire semaphore to stall further responses
      responseSemaphore.acquire();

      // when: write a single trace and flush
      // with responseSemaphore held, the response is blocked but may still time out
      writer.write(minimalTrace);
      numWritten += 1;

      assertEquals(0, numFailedPublish.get());
      assertEquals(numWritten, numPublished.get());
      assertEquals(numWritten, numPublished.get() + numFailedPublish.get());
      assertEquals(1, numFlushes.get());

      // when: send many traces to fill the sender queue...
      //   loop until outstanding requests > finished requests
      while (writer.traceProcessingWorker.getRemainingCapacity() > 0
          || numFailedPublish.get() == 0) {
        writer.write(minimalTrace);
        numWritten += 1;
      }

      assertTrue(numFailedPublish.get() > 0);
      assertEquals(numWritten, numPublished.get() + numFailedPublish.get());

      // with both disruptor & queue full, should reject everything
      int expectedRejects = 100;
      for (int i = 1; i <= expectedRejects; i++) {
        writer.write(minimalTrace);
        numWritten += 1;
      }

      assertEquals(numWritten, numPublished.get() + numFailedPublish.get());
    } finally {
      responseSemaphore.release();
      writer.close();
      intake.close();
    }
  }

  @Test
  void multiThreaded() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    AtomicInteger numPublished = new AtomicInteger(0);
    AtomicInteger numFailedPublish = new AtomicInteger(0);
    AtomicInteger numRepSent = new AtomicInteger(0);

    List<DDSpan> minimalTrace = createMinimalTrace();
    String path = buildIntakePath(trackType, apiVersion);

    JavaTestHttpServer intake =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(h -> h.post(path, api -> api.getResponse().status(200).send())));

    // This test focuses just on failed publish, so not verifying every callback
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    doAnswer(
            invocation -> {
              numPublished.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onPublish(any(), anyInt());
    doAnswer(
            invocation -> {
              numFailedPublish.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFailedPublish(anyInt(), anyInt());
    doAnswer(
            invocation -> {
              int repCount = invocation.getArgument(0);
              numRepSent.addAndGet(repCount);
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());

    HttpUrl hostUrl = HttpUrl.get(intake.getAddress());
    okhttp3.OkHttpClient httpClient = OkHttpUtils.buildHttpClient(hostUrl, 1000);
    DDIntakeApi api =
        DDIntakeApi.builder()
            .hostUrl(hostUrl)
            .httpClient(httpClient)
            .apiKey("my-api-key")
            .trackType(trackType)
            .build();
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .alwaysFlush(false)
            .build();
    writer.start();

    try {
      Runnable producer =
          () -> {
            for (int i = 1; i <= 100; i++) {
              writer.write(minimalTrace);
            }
          };

      Thread t1 = new Thread(producer);
      t1.start();

      Thread t2 = new Thread(producer);
      t2.start();

      t1.join();
      t2.join();

      writer.flush();

      // then: conditions.eventually { assert numPublished.get() == 200 && numRepSent.get() == 200 }
      int totalTraces = 100 + 100;
      long deadline = System.currentTimeMillis() + 5000;
      while (System.currentTimeMillis() < deadline) {
        if (numPublished.get() == totalTraces && numRepSent.get() == totalTraces) {
          break;
        }
        Thread.sleep(50);
      }
      assertEquals(totalTraces, numPublished.get());
      assertEquals(totalTraces, numRepSent.get());
    } finally {
      writer.close();
      intake.close();
    }
  }

  @Test
  void statsdSuccess() {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    AtomicInteger numTracesAccepted = new AtomicInteger(0);
    AtomicInteger numRequests = new AtomicInteger(0);
    AtomicInteger numResponses = new AtomicInteger(0);

    List<DDSpan> minimalTrace = createMinimalTrace();
    String path = buildIntakePath(trackType, apiVersion);

    JavaTestHttpServer intake =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(h -> h.post(path, api -> api.getResponse().status(200).send())));

    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    doAnswer(
            invocation -> {
              numTracesAccepted.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onPublish(any(), anyInt());
    doAnswer(
            invocation -> {
              numRequests.incrementAndGet();
              numResponses.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());

    HttpUrl hostUrl = HttpUrl.get(intake.getAddress());
    okhttp3.OkHttpClient httpClient = OkHttpUtils.buildHttpClient(hostUrl, 1000);
    DDIntakeApi api =
        DDIntakeApi.builder()
            .hostUrl(hostUrl)
            .httpClient(httpClient)
            .apiKey("my-api-key")
            .trackType(trackType)
            .build();
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .alwaysFlush(false)
            .build();
    writer.start();

    try {
      writer.write(minimalTrace);
      writer.flush();

      assertEquals(1, numTracesAccepted.get());
      assertEquals(1, numRequests.get());
      assertEquals(1, numResponses.get());
    } finally {
      intake.close();
      writer.close();
    }
  }

  @Test
  void statsdCommFailure() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    List<DDSpan> minimalTrace = createMinimalTrace();

    DDIntakeApi api = mock(DDIntakeApi.class);
    doAnswer(invocation -> RemoteApi.Response.failed(new IOException("comm error")))
        .when(api)
        .sendSerializedTraces(any());

    CountDownLatch latch = new CountDownLatch(2);
    StatsDClient statsd = mock(StatsDClient.class);
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsd, 100, TimeUnit.MILLISECONDS);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(trackType, api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .alwaysFlush(false)
            .build();
    healthMetrics.start();
    writer.start();

    // Set up stubs with countDown BEFORE the action
    doAnswer(
            invocation -> {
              latch.countDown();
              return null;
            })
        .when(statsd)
        .count(anyString(), anyLong());

    writer.write(minimalTrace);
    writer.flush();
    latch.await(10, TimeUnit.SECONDS);

    verify(statsd, times(1)).count("api.requests.total", 1L);
    verify(statsd, never()).incrementCounter("api.responses.total");
    verify(statsd, times(1)).count("api.errors.total", 1L);

    writer.close();
    healthMetrics.close();
  }

  static String buildIntakePath(TrackType trackType, String apiVersion) {
    return String.format("/api/%s/%s", apiVersion, trackType.name().toLowerCase());
  }

  static int calculateSize(List<DDSpan> trace, RemoteMapper mapper) {
    AtomicInteger size = new AtomicInteger();
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(
                mapper.messageBufferSize(),
                (messageCount, buffer) -> size.set(buffer.limit() - buffer.position())));
    packer.format(trace, mapper);
    packer.flush();
    return size.get();
  }
}
