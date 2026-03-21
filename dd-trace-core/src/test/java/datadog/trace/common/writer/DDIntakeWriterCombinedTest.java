package datadog.trace.common.writer;

import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.intake.TrackType;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ddintake.DDIntakeApi;
import datadog.trace.common.writer.ddintake.DDIntakeMapperDiscovery;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.PendingTrace;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.TracerHealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class DDIntakeWriterCombinedTest extends DDCoreSpecification {

  static CiVisibilityWellKnownTags wellKnownTags =
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
  CoreTracer dummyTracer;
  HttpServer activeServer;

  @BeforeEach
  void setUp() throws Exception {
    dummyTracer = tracerBuilder().writer(new ListWriter()).build();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (dummyTracer != null) {
      dummyTracer.close();
    }
    if (activeServer != null) {
      activeServer.stop(0);
      activeServer = null;
    }
  }

  @Test
  void noInteractionsBecauseOfInitialFlush() throws Exception {
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
    clearInvocations(api);

    writer.flush();

    verifyNoMoreInteractions(api);
    writer.close();
  }

  @Test
  void testHappyPath() throws Exception {
    DDIntakeApi api = mock(DDIntakeApi.class);
    when(api.sendSerializedTraces(argThat(p -> p.traceCount() == 2)))
        .thenReturn(RemoteApi.Response.success(200));

    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.CITESTCYCLE, api)
            .traceBufferSize(1024)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .alwaysFlush(false)
            .build();
    writer.start();
    clearInvocations(api);
    List<DDSpan> trace =
        Collections.singletonList((DDSpan) dummyTracer.buildSpan("fakeOperation").start());

    writer.write(trace);
    writer.write(trace);
    writer.flush();

    verify(api, times(1)).sendSerializedTraces(argThat(p -> p.traceCount() == 2));
    verifyNoMoreInteractions(api);
    writer.close();
  }

  @Test
  void testFloodOfTraces() throws Exception {
    int traceCount = 100;
    DDIntakeApi api = mock(DDIntakeApi.class);
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));

    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.CITESTCYCLE, api)
            .traceBufferSize(1024)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .alwaysFlush(false)
            .build();
    writer.start();
    clearInvocations(api);
    List<DDSpan> trace =
        Collections.singletonList((DDSpan) dummyTracer.buildSpan("fakeOperation").start());

    for (int i = 0; i < traceCount; i++) {
      writer.write(trace);
    }
    writer.flush();

    verify(api, times(1)).sendSerializedTraces(argThat(p -> p.traceCount() <= traceCount));
    verifyNoMoreInteractions(api);
    writer.close();
  }

  @Test
  void testFlushByTime() throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDIntakeApi api = mock(DDIntakeApi.class);
    CountDownLatch latch = new CountDownLatch(1);
    when(api.sendSerializedTraces(argThat(p -> p.traceCount() == 5)))
        .thenReturn(RemoteApi.Response.success(200));
    doAnswer(
            inv -> {
              latch.countDown();
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());

    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.CITESTCYCLE, api)
            .healthMetrics(healthMetrics)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(1000)
            .alwaysFlush(false)
            .build();
    writer.start();
    DDSpan span = (DDSpan) dummyTracer.buildSpan("fakeOperation").start();
    java.util.List<DDSpan> trace = new java.util.ArrayList<>();
    for (int i = 0; i < 10; i++) {
      trace.add(span);
    }

    for (int i = 0; i < 5; i++) {
      writer.write(trace);
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    verify(healthMetrics, times(1)).onSerialize(anyInt());
    verify(api, times(1)).sendSerializedTraces(argThat(p -> p.traceCount() == 5));

    writer.close();
  }

  @Timeout(30)
  @Test
  void testDefaultBufferSize() throws Exception {
    DDIntakeApi api = mock(DDIntakeApi.class);
    when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));

    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.CITESTCYCLE, api)
            .wellKnownTags(wellKnownTags)
            .traceBufferSize(1024)
            .prioritization(ENSURE_TRACE)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .alwaysFlush(false)
            .build();
    writer.start();
    clearInvocations(api);

    DDIntakeMapperDiscovery discovery =
        new DDIntakeMapperDiscovery(TrackType.CITESTCYCLE, wellKnownTags, false);
    discovery.discover();
    RemoteMapper mapper = (RemoteMapper) discovery.getMapper();
    List<DDSpan> minimalTrace = createMinimalTrace();
    int traceSize = calculateSize(minimalTrace, mapper);
    int maxedPayloadTraceCount = (int) (mapper.messageBufferSize() / traceSize);

    for (int i = 0; i <= maxedPayloadTraceCount; i++) {
      writer.write(minimalTrace);
    }
    writer.flush();

    verify(api).sendSerializedTraces(argThat(p -> p.traceCount() == maxedPayloadTraceCount));
    verify(api).sendSerializedTraces(argThat(p -> p.traceCount() == 1));
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @Test
  void checkThatThereAreNoInteractionsAfterClose() throws Exception {
    DDIntakeApi api = mock(DDIntakeApi.class);
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.CITESTCYCLE, api)
            .healthMetrics(healthMetrics)
            .monitoring(monitoring)
            .alwaysFlush(false)
            .build();
    writer.start();
    clearInvocations(api);

    writer.close();
    writer.write(Collections.<DDSpan>emptyList());
    writer.flush();

    verify(healthMetrics, times(1)).onFailedPublish(anyInt(), anyInt());
    verify(healthMetrics, times(1)).onFlush(anyBoolean());
    verify(healthMetrics, times(1)).onShutdown(anyBoolean());
    verify(healthMetrics, times(1)).close();
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @Test
  void monitorHappyPath() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();

    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          readAllBytes(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    HttpUrl hostUrl = HttpUrl.get("http://localhost:" + intake.getAddress().getPort());
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

    writer.start();
    verify(healthMetrics).onStart((int) writer.getCapacity());

    writer.write(minimalTrace);
    writer.flush();

    verify(healthMetrics).onPublish(eq(minimalTrace), anyInt());
    verify(healthMetrics).onSerialize(anyInt());
    verify(healthMetrics).onFlush(false);
    verify(healthMetrics)
        .onSend(
            eq(1),
            anyInt(),
            argThat(r -> r.success() && r.status().isPresent() && r.status().getAsInt() == 200));

    writer.close();
    verify(healthMetrics).onShutdown(true);
  }

  @Test
  void monitorIntakeReturnsError() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();

    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          readAllBytes(exchange);
          exchange.sendResponseHeaders(500, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    HttpUrl hostUrl = HttpUrl.get("http://localhost:" + intake.getAddress().getPort());
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

    writer.start();
    verify(healthMetrics).onStart((int) writer.getCapacity());

    writer.write(minimalTrace);
    writer.flush();

    verify(healthMetrics).onPublish(eq(minimalTrace), anyInt());
    verify(healthMetrics).onSerialize(anyInt());
    verify(healthMetrics).onFlush(false);
    verify(healthMetrics)
        .onFailedSend(
            eq(1),
            anyInt(),
            argThat(r -> !r.success() && r.status().isPresent() && r.status().getAsInt() == 500));

    writer.close();
    verify(healthMetrics).onShutdown(true);
  }

  @Test
  void unreachableIntakeTest() throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();

    DDIntakeApi api = mock(DDIntakeApi.class);
    when(api.sendSerializedTraces(any()))
        .thenReturn(RemoteApi.Response.failed(new IOException("comm error")));

    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.CITESTCYCLE, api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .alwaysFlush(false)
            .build();

    writer.start();
    verify(healthMetrics).onStart((int) writer.getCapacity());

    writer.write(minimalTrace);
    writer.flush();

    verify(healthMetrics).onPublish(any(), anyInt());
    verify(healthMetrics).onFlush(false);

    writer.close();
    verify(healthMetrics).onShutdown(true);
  }

  @Test
  void slowResponseTest() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    AtomicInteger numWritten = new AtomicInteger(0);
    AtomicInteger numFlushes = new AtomicInteger(0);
    AtomicInteger numPublished = new AtomicInteger(0);
    AtomicInteger numFailedPublish = new AtomicInteger(0);
    AtomicInteger numRequests = new AtomicInteger(0);
    AtomicInteger numFailedRequests = new AtomicInteger(0);

    Semaphore responseSemaphore = new Semaphore(1);
    int bufferSize = 16;
    List<DDSpan> minimalTrace = createMinimalTrace();

    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          try {
            readAllBytes(exchange);
            responseSemaphore.acquire();
            try {
              exchange.sendResponseHeaders(200, 0);
              exchange.getResponseBody().close();
            } finally {
              responseSemaphore.release();
            }
          } catch (Exception e) {
            // ignore
          }
        });
    intake.start();

    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    doAnswer(
            inv -> {
              numPublished.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onPublish(any(), anyInt());
    doAnswer(
            inv -> {
              numFailedPublish.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFailedPublish(anyInt(), anyInt());
    doAnswer(
            inv -> {
              numFlushes.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFlush(anyBoolean());
    doAnswer(
            inv -> {
              numRequests.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());
    doAnswer(
            inv -> {
              numFailedRequests.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFailedSend(anyInt(), anyInt(), any());

    HttpUrl hostUrl = HttpUrl.get("http://localhost:" + intake.getAddress().getPort());
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

    responseSemaphore.acquire();
    responseSemaphore.release();
    writer.flush();
    responseSemaphore.acquire();

    writer.write(minimalTrace);
    numWritten.incrementAndGet();

    assertEquals(0, numFailedPublish.get());
    assertEquals(numWritten.get(), numPublished.get());
    assertEquals(numWritten.get(), numPublished.get() + numFailedPublish.get());
    assertEquals(1, numFlushes.get());

    while (writer.traceProcessingWorker.getRemainingCapacity() > 0 || numFailedPublish.get() == 0) {
      writer.write(minimalTrace);
      numWritten.incrementAndGet();
    }

    assertTrue(numFailedPublish.get() > 0);
    assertEquals(numWritten.get(), numPublished.get() + numFailedPublish.get());

    int expectedRejects = 100;
    for (int i = 0; i < expectedRejects; i++) {
      writer.write(minimalTrace);
      numWritten.incrementAndGet();
    }

    assertEquals(numWritten.get(), numPublished.get() + numFailedPublish.get());

    responseSemaphore.release();
    writer.close();
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
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          readAllBytes(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    doAnswer(
            inv -> {
              numPublished.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onPublish(any(), anyInt());
    doAnswer(
            inv -> {
              numFailedPublish.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onFailedPublish(anyInt(), anyInt());
    doAnswer(
            inv -> {
              numRepSent.addAndGet((Integer) inv.getArgument(0));
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());

    HttpUrl hostUrl = HttpUrl.get("http://localhost:" + intake.getAddress().getPort());
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

    Runnable producer =
        () -> {
          for (int i = 0; i < 100; i++) {
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

    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      if (numPublished.get() + numFailedPublish.get() == 200
          && numRepSent.get() == numPublished.get()) {
        break;
      }
      Thread.sleep(100);
    }

    assertEquals(200, numPublished.get() + numFailedPublish.get());
    assertEquals(numPublished.get(), numRepSent.get());

    writer.close();
  }

  @Test
  void statsdSuccess() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    AtomicInteger numTracesAccepted = new AtomicInteger(0);
    AtomicInteger numRequests = new AtomicInteger(0);
    AtomicInteger numResponses = new AtomicInteger(0);

    List<DDSpan> minimalTrace = createMinimalTrace();

    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          readAllBytes(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    doAnswer(
            inv -> {
              numTracesAccepted.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onPublish(any(), anyInt());
    doAnswer(
            inv -> {
              numRequests.incrementAndGet();
              numResponses.incrementAndGet();
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());

    HttpUrl hostUrl = HttpUrl.get("http://localhost:" + intake.getAddress().getPort());
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

    writer.write(minimalTrace);
    writer.flush();

    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline && numRequests.get() == 0) {
      Thread.sleep(50);
    }

    assertEquals(1, numTracesAccepted.get());
    assertEquals(1, numRequests.get());
    assertEquals(1, numResponses.get());

    intake.stop(0);
    activeServer = null;
    writer.close();
  }

  @Test
  void statsdCommFailure() throws Exception {
    List<DDSpan> minimalTrace = createMinimalTrace();

    DDIntakeApi api = mock(DDIntakeApi.class);
    when(api.sendSerializedTraces(any()))
        .thenReturn(RemoteApi.Response.failed(new IOException("comm error")));

    CountDownLatch latch = new CountDownLatch(2);
    StatsDClient statsd = mock(StatsDClient.class);
    doAnswer(
            inv -> {
              latch.countDown();
              return null;
            })
        .when(statsd)
        .count(org.mockito.Mockito.eq("api.requests.total"), org.mockito.Mockito.eq(1L), any());
    doAnswer(
            inv -> {
              latch.countDown();
              return null;
            })
        .when(statsd)
        .count(org.mockito.Mockito.eq("api.errors.total"), org.mockito.Mockito.eq(1L), any());

    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsd, 100, TimeUnit.MILLISECONDS);
    DDIntakeWriter writer =
        DDIntakeWriter.builder()
            .addTrack(TrackType.CITESTCYCLE, api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .alwaysFlush(false)
            .build();
    healthMetrics.start();
    writer.start();

    writer.write(minimalTrace);
    writer.flush();
    assertTrue(latch.await(10, TimeUnit.SECONDS));

    verify(statsd)
        .count(org.mockito.Mockito.eq("api.requests.total"), org.mockito.Mockito.eq(1L), any());
    verify(statsd, org.mockito.Mockito.never())
        .incrementCounter(org.mockito.Mockito.eq("api.responses.total"), any());
    verify(statsd)
        .count(org.mockito.Mockito.eq("api.errors.total"), org.mockito.Mockito.eq(1L), any());

    writer.close();
    healthMetrics.close();
  }

  private DDSpanContext createMinimalContext() {
    CoreTracer tracer = mock(CoreTracer.class);
    PendingTrace trace = mock(PendingTrace.class);
    when(trace.mapServiceName(any())).thenAnswer(inv -> inv.getArgument(0));
    when(trace.getTracer()).thenReturn(tracer);
    return new DDSpanContext(
        DDTraceId.ONE,
        1,
        DDSpanId.ZERO,
        "",
        "",
        "",
        "",
        (int) PrioritySampling.UNSET,
        "",
        Collections.<String, String>emptyMap(),
        false,
        "",
        0,
        trace,
        null,
        null,
        NoopPathwayContext.INSTANCE,
        false,
        PropagationTags.factory().empty());
  }

  List<DDSpan> createMinimalTrace() throws Exception {
    DDSpanContext context = createMinimalContext();
    DDSpan minimalSpan = DDSpan.create("test", 0, context, null);
    return Collections.singletonList(minimalSpan);
  }

  static int calculateSize(List<DDSpan> trace, RemoteMapper mapper) throws Exception {
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

  static String buildIntakePath(TrackType trackType, String apiVersion) {
    return String.format("/api/%s/%s", apiVersion, trackType.name().toLowerCase());
  }

  static byte[] readAllBytes(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    try (InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = is.read(buf)) != -1) {
        baos.write(buf, 0, n);
      }
      return baos.toByteArray();
    }
  }
}
