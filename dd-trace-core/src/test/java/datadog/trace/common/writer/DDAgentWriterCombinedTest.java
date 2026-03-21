package datadog.trace.common.writer;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static datadog.trace.common.writer.ddagent.Prioritization.ENSURE_TRACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.Mapper;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class DDAgentWriterCombinedTest extends DDCoreSpecification {

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

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void noInteractionsBecauseOfInitialFlush(String agentVersion) throws Exception {
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .agentApi(api)
            .traceBufferSize(8)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .build();
    writer.start();

    writer.flush();

    verifyNoMoreInteractions(api);
    writer.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void testHappyPath(String agentVersion) throws Exception {
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .traceBufferSize(1024)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .build();
    writer.start();
    List<DDSpan> trace =
        Collections.singletonList((DDSpan) dummyTracer.buildSpan("fakeOperation").start());

    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
    when(api.sendSerializedTraces(argThat(p -> p.traceCount() == 2)))
        .thenReturn(RemoteApi.Response.success(200));

    writer.write(trace);
    writer.write(trace);
    writer.flush();

    verify(discovery, times(2)).getTraceEndpoint();
    verify(api, times(1)).sendSerializedTraces(argThat(p -> p.traceCount() == 2));
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void testFloodOfTraces(String agentVersion) throws Exception {
    int bufferSize = 1024;
    int traceCount = 100;
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .traceBufferSize(bufferSize)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .build();
    writer.start();
    List<DDSpan> trace =
        Collections.singletonList((DDSpan) dummyTracer.buildSpan("fakeOperation").start());

    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
    when(api.sendSerializedTraces(argThat(p -> p.traceCount() <= traceCount)))
        .thenReturn(RemoteApi.Response.success(200));

    for (int i = 0; i < traceCount; i++) {
      writer.write(trace);
    }
    writer.flush();

    verify(discovery, times(2)).getTraceEndpoint();
    verify(api, times(1)).sendSerializedTraces(any());
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void testFlushByTime(String agentVersion) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    CountDownLatch latch = new CountDownLatch(1);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .healthMetrics(healthMetrics)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(1000)
            .build();
    writer.start();
    DDSpan span = (DDSpan) dummyTracer.buildSpan("fakeOperation").start();
    List<DDSpan> trace = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      trace.add(span);
    }

    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
    when(api.sendSerializedTraces(argThat(p -> p.traceCount() == 5)))
        .thenReturn(RemoteApi.Response.success(200));
    doAnswer(
            inv -> {
              latch.countDown();
              return null;
            })
        .when(healthMetrics)
        .onSend(anyInt(), anyInt(), any());

    for (int i = 0; i < 5; i++) {
      writer.write(trace);
    }
    assertTrue(latch.await(5, TimeUnit.SECONDS));

    verify(discovery, times(2)).getTraceEndpoint();
    verify(healthMetrics, times(1)).onSerialize(anyInt());
    verify(api, times(1)).sendSerializedTraces(argThat(p -> p.traceCount() == 5));

    writer.close();
  }

  @Timeout(30)
  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void testDefaultBufferSize(String agentVersion) throws Exception {
    injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "false");
    resetProcessTags();
    try {
      DDAgentApi api = mock(DDAgentApi.class);
      DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
      DDAgentWriter writer =
          DDAgentWriter.builder()
              .featureDiscovery(discovery)
              .agentApi(api)
              .traceBufferSize(1024)
              .prioritization(ENSURE_TRACE)
              .monitoring(monitoring)
              .flushIntervalMilliseconds(-1)
              .build();
      writer.start();

      List<DDSpan> minimalTrace = createMinimalTrace();
      RemoteMapper remapper =
          "v0.5/traces".equals(agentVersion) ? new TraceMapperV0_5() : new TraceMapperV0_4();
      int traceSize = calculateSize(minimalTrace, remapper);
      int maxedPayloadTraceCount = (int) (remapper.messageBufferSize() / traceSize);

      when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
      when(api.sendSerializedTraces(any())).thenReturn(RemoteApi.Response.success(200));

      for (int i = 0; i <= maxedPayloadTraceCount; i++) {
        writer.write(minimalTrace);
      }
      writer.flush();

      verify(discovery, times(2)).getTraceEndpoint();
      verify(api).sendSerializedTraces(argThat(p -> p.traceCount() == maxedPayloadTraceCount));
      verify(api).sendSerializedTraces(argThat(p -> p.traceCount() == 1));

      writer.close();
    } finally {
      injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true");
      resetProcessTags();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void checkThatThereAreNoInteractionsAfterClose(String agentVersion) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .healthMetrics(healthMetrics)
            .monitoring(monitoring)
            .build();
    writer.start();

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

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void monitorHappyPath(String agentVersion) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();

    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/" + agentVersion,
        exchange -> {
          readAllBytes(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    agent.start();

    HttpUrl agentUrl = HttpUrl.get("http://localhost:" + agent.getAddress().getPort());
    okhttp3.OkHttpClient httpClient = OkHttpUtils.buildHttpClient(agentUrl, 1000);
    DDAgentFeaturesDiscovery discovery =
        new DDAgentFeaturesDiscovery(httpClient, monitoring, agentUrl, true, true);
    DDAgentApi api = new DDAgentApi(httpClient, agentUrl, discovery, monitoring, true);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
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

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void monitorAgentReturnsError(String agentVersion) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();

    AtomicInteger callCount = new AtomicInteger(0);
    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/" + agentVersion,
        exchange -> {
          readAllBytes(exchange);
          int count = callCount.incrementAndGet();
          if (count == 1) {
            exchange.sendResponseHeaders(200, 0);
          } else {
            exchange.sendResponseHeaders(500, 0);
          }
          exchange.getResponseBody().close();
        });
    agent.start();

    HttpUrl agentUrl = HttpUrl.get("http://localhost:" + agent.getAddress().getPort());
    okhttp3.OkHttpClient httpClient = OkHttpUtils.buildHttpClient(agentUrl, 1000);
    DDAgentFeaturesDiscovery discovery =
        new DDAgentFeaturesDiscovery(httpClient, monitoring, agentUrl, true, true);
    DDAgentApi api = new DDAgentApi(httpClient, agentUrl, discovery, monitoring, true);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
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

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void unreachableAgentTest(String agentVersion) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);

    DDAgentApi api = mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any()))
        .thenReturn(RemoteApi.Response.failed(new IOException("comm error")));

    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
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

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void slowResponseTest(String agentVersion) throws Exception {
    AtomicInteger numWritten = new AtomicInteger(0);
    AtomicInteger numFlushes = new AtomicInteger(0);
    AtomicInteger numPublished = new AtomicInteger(0);
    AtomicInteger numFailedPublish = new AtomicInteger(0);
    AtomicInteger numRequests = new AtomicInteger(0);
    AtomicInteger numFailedRequests = new AtomicInteger(0);

    Semaphore responseSemaphore = new Semaphore(1);

    int bufferSize = 16;
    List<DDSpan> minimalTrace = createMinimalTrace();

    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/" + agentVersion,
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
    agent.start();

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

    DDAgentWriter writer =
        DDAgentWriter.builder()
            .traceAgentV05Enabled(true)
            .traceAgentPort(agent.getAddress().getPort())
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .traceBufferSize(bufferSize)
            .build();
    writer.start();

    responseSemaphore.acquire();

    writer.write(minimalTrace);
    numWritten.incrementAndGet();

    responseSemaphore.release();
    writer.flush();

    responseSemaphore.acquire();

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

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void multiThreaded(String agentVersion) throws Exception {
    AtomicInteger numPublished = new AtomicInteger(0);
    AtomicInteger numFailedPublish = new AtomicInteger(0);
    AtomicInteger numRepSent = new AtomicInteger(0);

    List<DDSpan> minimalTrace = createMinimalTrace();

    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/" + agentVersion,
        exchange -> {
          readAllBytes(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    agent.start();

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

    DDAgentWriter writer =
        DDAgentWriter.builder()
            .traceAgentV05Enabled(true)
            .traceAgentPort(agent.getAddress().getPort())
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
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
      int total = numPublished.get() + numFailedPublish.get();
      if (total == 200 && numRepSent.get() == numPublished.get()) {
        break;
      }
      Thread.sleep(100);
    }

    assertEquals(200, numPublished.get() + numFailedPublish.get());
    assertEquals(numPublished.get(), numRepSent.get());

    writer.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void statsdSuccess(String agentVersion) throws Exception {
    AtomicInteger numTracesAccepted = new AtomicInteger(0);
    AtomicInteger numRequests = new AtomicInteger(0);
    AtomicInteger numResponses = new AtomicInteger(0);

    List<DDSpan> minimalTrace = createMinimalTrace();

    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/" + agentVersion,
        exchange -> {
          readAllBytes(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    agent.start();

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

    DDAgentWriter writer =
        DDAgentWriter.builder()
            .agentHost("localhost")
            .traceAgentV05Enabled(true)
            .traceAgentPort(agent.getAddress().getPort())
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .build();
    writer.start();

    writer.write(minimalTrace);
    writer.flush();

    // Wait for async send
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline && numRequests.get() == 0) {
      Thread.sleep(50);
    }

    assertEquals(1, numTracesAccepted.get());
    assertEquals(1, numRequests.get());
    assertEquals(1, numResponses.get());

    writer.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {"v0.3/traces", "v0.4/traces", "v0.5/traces"})
  void statsdCommFailure(String agentVersion) throws Exception {
    List<DDSpan> minimalTrace = createMinimalTrace();

    DDAgentApi api = mock(DDAgentApi.class);
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
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .traceAgentV05Enabled(true)
            .agentApi(api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
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

  @SuppressWarnings({"rawtypes", "unchecked"})
  static int calculateSize(List<DDSpan> trace, RemoteMapper mapper) throws Exception {
    AtomicInteger size = new AtomicInteger();
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(
                1024, (messageCount, buffer) -> size.set(buffer.limit() - buffer.position())));
    packer.format(trace, (Mapper) mapper);
    packer.flush();
    return size.get();
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

  static void resetProcessTags() throws Exception {
    java.lang.reflect.Method m = datadog.trace.api.ProcessTags.class.getDeclaredMethod("reset");
    m.setAccessible(true);
    m.invoke(null);
  }
}
