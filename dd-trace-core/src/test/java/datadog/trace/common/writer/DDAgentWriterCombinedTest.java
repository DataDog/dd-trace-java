package datadog.trace.common.writer;

import static datadog.trace.api.ProtocolVersion.V0_5;
import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.TraceMapper;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.TracerHealthMetrics;
import datadog.trace.junit.utils.config.WithConfig;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.tabletest.junit.TableTest;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DDAgentWriterCombinedTest extends DDCoreJavaSpecification {

  // DDAgentWriter default buffer size (matches private DDAgentWriter.BUFFER_SIZE)
  private static final int AGENT_WRITER_BUFFER_SIZE = 1024;

  MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
  Phaser phaser = new Phaser();

  // Only used to create spans
  CoreTracer dummyTracer;

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

  @BeforeEach
  void resetProcessTags() {
    // Sync ProcessTags.enabled with the current Config (which may be modified by @WithConfig)
    ProcessTags.reset(Config.get());
  }

  List<DDSpan> createMinimalTrace() {
    // Use buildSpan from DDCoreJavaSpecification to create a real DDSpan with minimal fields
    DDSpan span = buildSpan(0L, "", Collections.emptyMap());
    return Collections.singletonList(span);
  }

  @Test
  void noInteractionsBecauseOfInitialFlush() {
    DDAgentApi api = Mockito.mock(DDAgentApi.class);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .agentApi(api)
            .traceBufferSize(8)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .build();
    writer.start();

    writer.flush();

    // then: 0 * _ (no interactions at all on mocked api)
    verifyNoMoreInteractions(api);

    writer.close();
  }

  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void testHappyPath(String agentVersion) {
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
    DDSpan span = (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start();
    List<DDSpan> trace = Collections.singletonList(span);

    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
    when(api.sendSerializedTraces(argThat(payload -> payload.traceCount() == 2)))
        .thenReturn(RemoteApi.Response.success(200));

    writer.write(trace);
    writer.write(trace);
    writer.flush();

    verify(discovery, times(2)).getTraceEndpoint();
    verify(api, times(1)).sendSerializedTraces(argThat(payload -> payload.traceCount() == 2));
    verifyNoMoreInteractions(api, discovery);

    writer.close();
  }

  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void testFloodOfTraces(String agentVersion) {
    // bufferSize = 1024; traceCount = 100 (shouldn't trigger payload, but bigger than disruptor
    // size)
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
    DDSpan span = (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start();
    List<DDSpan> trace = Collections.singletonList(span);

    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
    when(api.sendSerializedTraces(argThat(payload -> payload.traceCount() <= traceCount)))
        .thenReturn(RemoteApi.Response.success(200));

    for (int i = 1; i <= traceCount; i++) {
      writer.write(trace);
    }
    writer.flush();

    verify(discovery, times(2)).getTraceEndpoint();
    verify(api, times(1))
        .sendSerializedTraces(argThat(payload -> payload.traceCount() <= traceCount));
    verifyNoMoreInteractions(api, discovery);

    writer.close();
  }

  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void testFlushByTime(String agentVersion) throws Exception {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .healthMetrics(healthMetrics)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(1000)
            .build();
    writer.start();
    DDSpan span = (DDSpan) dummyTracer.buildSpan("datadog", "fakeOperation").start();
    List<DDSpan> trace = Collections.nCopies(10, span);

    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
    when(api.sendSerializedTraces(argThat(payload -> payload.traceCount() == 5)))
        .thenReturn(RemoteApi.Response.success(200));

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

    verify(discovery, times(2)).getTraceEndpoint();
    verify(healthMetrics, times(1)).onSerialize(anyInt());
    verify(api, times(1)).sendSerializedTraces(argThat(payload -> payload.traceCount() == 5));
    // _ * healthMetrics.onPublish(_, _) means any number, so don't verify count
    verify(healthMetrics).onSend(anyInt(), anyInt(), any());
    verifyNoMoreInteractions(api, discovery);

    writer.close();
  }

  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  @WithConfig(key = EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, value = "false")
  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void testDefaultBufferSizeFor(String agentVersion) {
    // setup: disable process tags since they are only written on the first span
    // and it will break the trace size estimation
    List<DDSpan> minimalTrace = createMinimalTrace();
    DDAgentApi api = mock(DDAgentApi.class);
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .traceBufferSize(AGENT_WRITER_BUFFER_SIZE)
            .prioritization(ENSURE_TRACE)
            .monitoring(monitoring)
            .flushIntervalMilliseconds(-1)
            .build();
    writer.start();

    TraceMapper mapper =
        agentVersion.equals("v0.5/traces") ? new TraceMapperV0_5() : new TraceMapperV0_4();
    int traceSize = calculateSize(minimalTrace, mapper);
    int maxedPayloadTraceCount = (mapper.messageBufferSize() / traceSize);

    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
    when(api.sendSerializedTraces(
            argThat(payload -> payload != null && payload.traceCount() == maxedPayloadTraceCount)))
        .thenReturn(RemoteApi.Response.success(200));
    when(api.sendSerializedTraces(argThat(payload -> payload != null && payload.traceCount() == 1)))
        .thenReturn(RemoteApi.Response.success(200));

    for (int i = 0; i <= maxedPayloadTraceCount; i++) {
      writer.write(minimalTrace);
    }
    writer.flush();

    verify(discovery, times(2)).getTraceEndpoint();
    verify(api, times(1))
        .sendSerializedTraces(
            argThat(payload -> payload != null && payload.traceCount() == maxedPayloadTraceCount));
    verify(api, times(1))
        .sendSerializedTraces(argThat(payload -> payload != null && payload.traceCount() == 1));
    verifyNoMoreInteractions(api, discovery);

    writer.close();
  }

  @Test
  void checkThatThereAreNoInteractionsAfterClose() {
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
    // Clear invocations from start() (Spock only counts interactions in when: blocks)
    clearInvocations(healthMetrics, api, discovery);

    writer.close();
    writer.write(Collections.emptyList());
    writer.flush();

    // then: this will be checked during flushing
    verify(healthMetrics, times(1)).onFailedPublish(anyInt(), anyInt());
    verify(healthMetrics, times(1)).onFlush(any(Boolean.class));
    verify(healthMetrics, times(1)).onShutdown(any(Boolean.class));
    verify(healthMetrics, times(1)).close();
    verifyNoMoreInteractions(healthMetrics, api, discovery);

    writer.close();
  }

  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void monitorHappyPath(String agentVersion) {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();

    // DQH -- need to set-up a dummy agent for the final send callback to work
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    h -> h.put(agentVersion, api -> api.getResponse().status(200).send())));
    try {
      HttpUrl agentUrl = HttpUrl.get(agent.getAddress());
      okhttp3.OkHttpClient client = OkHttpUtils.buildHttpClient(agentUrl, 1000);
      DDAgentFeaturesDiscovery discovery =
          new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, V0_5, true, false);
      DDAgentApi api = new DDAgentApi(client, agentUrl, discovery, monitoring, true);
      DDAgentWriter writer =
          DDAgentWriter.builder()
              .featureDiscovery(discovery)
              .agentApi(api)
              .monitoring(monitoring)
              .healthMetrics(healthMetrics)
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
      agent.close();
    }
  }

  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void monitorAgentReturnsError(String agentVersion) {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();

    // DQH -- need to set-up a dummy agent for the final send callback to work
    final boolean[] first = {true};
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    h ->
                        h.put(
                            agentVersion,
                            api -> {
                              // DQH - DDApi sniffs for end point existence, so respond with 200 the
                              // first time
                              if (first[0]) {
                                api.getResponse().status(200).send();
                                first[0] = false;
                              } else {
                                api.getResponse().status(500).send();
                              }
                            })));
    try {
      HttpUrl agentUrl = HttpUrl.get(agent.getAddress());
      okhttp3.OkHttpClient client = OkHttpUtils.buildHttpClient(agentUrl, 1000);
      DDAgentFeaturesDiscovery discovery =
          new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, V0_5, true, false);
      DDAgentApi api = new DDAgentApi(client, agentUrl, discovery, monitoring, true);
      DDAgentWriter writer =
          DDAgentWriter.builder()
              .featureDiscovery(discovery)
              .agentApi(api)
              .monitoring(monitoring)
              .healthMetrics(healthMetrics)
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
      agent.close();
    }
  }

  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void unreachableAgentTest(String agentVersion) {
    HealthMetrics healthMetrics = mock(HealthMetrics.class);
    List<DDSpan> minimalTrace = createMinimalTrace();
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.getTraceEndpoint()).thenReturn(agentVersion);
    DDAgentApi api = mock(DDAgentApi.class);
    // simulating a communication failure to a server
    when(api.sendSerializedTraces(any()))
        .thenReturn(RemoteApi.Response.failed(new IOException("comm error")));

    DDAgentWriter writer =
        DDAgentWriter.builder()
            .featureDiscovery(discovery)
            .agentApi(api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
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
  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void slowResponseTest(String agentVersion) throws Exception {
    int numWritten = 0;
    AtomicInteger numFlushes = new AtomicInteger(0);
    AtomicInteger numPublished = new AtomicInteger(0);
    AtomicInteger numFailedPublish = new AtomicInteger(0);
    AtomicInteger numRequests = new AtomicInteger(0);
    AtomicInteger numFailedRequests = new AtomicInteger(0);

    Semaphore responseSemaphore = new Semaphore(1);

    // Need to set-up a dummy agent for the final send callback to work
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    h ->
                        h.put(
                            agentVersion,
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

    int bufferSize = 16;
    List<DDSpan> minimalTrace = createMinimalTrace();
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .traceAgentProtocolVersion(V0_5)
            .traceAgentPort(agent.getAddress().getPort())
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .traceBufferSize(bufferSize)
            .build();
    writer.start();

    // gate responses
    responseSemaphore.acquire();

    try {
      // when: write a single trace and flush
      // with responseSemaphore held, the response is blocked but may still time out
      writer.write(minimalTrace);
      numWritten += 1;

      // sanity check coordination mechanism of test
      // release to allow response to be generated
      responseSemaphore.release();
      writer.flush();

      // reacquire semaphore to stall further responses
      responseSemaphore.acquire();

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
      agent.close();
    }
  }

  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void multiThreaded(String agentVersion) throws Exception {
    AtomicInteger numPublished = new AtomicInteger(0);
    AtomicInteger numFailedPublish = new AtomicInteger(0);
    AtomicInteger numRepSent = new AtomicInteger(0);

    List<DDSpan> minimalTrace = createMinimalTrace();

    // Need to set-up a dummy agent for the final send callback to work
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    h -> h.put(agentVersion, api -> api.getResponse().status(200).send())));

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

    DDAgentWriter writer =
        DDAgentWriter.builder()
            .traceAgentProtocolVersion(V0_5)
            .traceAgentPort(agent.getAddress().getPort())
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
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
      agent.close();
    }
  }

  @TableTest({
    "scenario | agentVersion ",
    "v0.3     | 'v0.3/traces'",
    "v0.4     | 'v0.4/traces'",
    "v0.5     | 'v0.5/traces'"
  })
  void statsdSuccess(String agentVersion) {
    AtomicInteger numTracesAccepted = new AtomicInteger(0);
    AtomicInteger numRequests = new AtomicInteger(0);
    AtomicInteger numResponses = new AtomicInteger(0);

    List<DDSpan> minimalTrace = createMinimalTrace();

    // Need to set-up a dummy agent for the final send callback to work
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            server ->
                server.handlers(
                    h -> h.put(agentVersion, api -> api.getResponse().status(200).send())));

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
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .agentHost(agent.getAddress().getHost())
            .traceAgentProtocolVersion(V0_5)
            .traceAgentPort(agent.getAddress().getPort())
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .build();
    writer.start();

    try {
      writer.write(minimalTrace);
      writer.flush();

      assertEquals(1, numTracesAccepted.get());
      assertEquals(1, numRequests.get());
      assertEquals(1, numResponses.get());
    } finally {
      agent.close();
      writer.close();
    }
  }

  @Test
  void statsdCommFailure() throws Exception {
    List<DDSpan> minimalTrace = createMinimalTrace();

    DDAgentApi api = Mockito.mock(DDAgentApi.class);
    when(api.sendSerializedTraces(any()))
        .thenReturn(RemoteApi.Response.failed(new IOException("comm error")));

    CountDownLatch latch = new CountDownLatch(2);
    StatsDClient statsd = mock(StatsDClient.class);
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsd, 100, TimeUnit.MILLISECONDS);
    DDAgentWriter writer =
        DDAgentWriter.builder()
            .traceAgentProtocolVersion(V0_5)
            .agentApi(api)
            .monitoring(monitoring)
            .healthMetrics(healthMetrics)
            .build();
    healthMetrics.start();
    writer.start();

    // stub statsd.count for latch coordination - called with varargs String... tags
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
    verify(statsd, times(0)).incrementCounter("api.responses.total");
    verify(statsd, times(1)).count("api.errors.total", 1L);

    writer.close();
    healthMetrics.close();
  }

  static int calculateSize(List<DDSpan> trace, TraceMapper mapper) {
    AtomicInteger size = new AtomicInteger();
    MsgPackWriter packer =
        new MsgPackWriter(
            new FlushingBuffer(
                1024, (messageCount, buffer) -> size.set(buffer.limit() - buffer.position())));
    packer.format(trace, mapper);
    packer.flush();
    return size.get();
  }
}
