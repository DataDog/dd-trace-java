package datadog.trace.core.otlp.metrics;

import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_ENDPOINT;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_PROTOCOL;
import static datadog.trace.common.writer.RemoteApi.Response.failed;
import static datadog.trace.common.writer.RemoteApi.Response.success;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.metrics.CompletableResultCode;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class OtlpMetricsServiceTest {
  private static final OtlpPayload PAYLOAD =
      new OtlpPayload(ByteBuffer.wrap(new byte[] {1}), OtlpPayload.PROTOBUF_CONTENT_TYPE);
  private final List<ScheduledExecutorService> executors = new ArrayList<>();
  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  @AfterEach
  void stopExecutors() {
    executors.forEach(ScheduledExecutorService::shutdownNow);
    AgentTracer.forceRegister(originalTracer);
  }

  @Test
  void httpJsonProtocolUsesJsonCollectorAndConfiguredEndpoint() {
    Properties properties = new Properties();
    properties.setProperty(OTLP_METRICS_PROTOCOL, "http/json");
    properties.setProperty(OTLP_METRICS_ENDPOINT, "http://localhost:4318/v1/metrics");

    OtlpMetricsService service = new OtlpMetricsService(Config.get(properties));

    assertInstanceOf(OtlpMetricsJsonCollector.class, service.getCollector());
    OtlpHttpSender sender = assertInstanceOf(OtlpHttpSender.class, service.getSender());
    assertEquals("http://localhost:4318/v1/metrics", sender.url().toString());
    assertTrue(service.shutdown().join(5, SECONDS).isSuccess());
  }

  @Test
  void flushExportsPendingMetrics() {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));

    test.service.flush();

    verify(test.sender, timeout(5_000)).send(PAYLOAD);
  }

  @Test
  void emptyFlushSkipsTransport() {
    TestService test = service(OtlpPayload.EMPTY);

    test.service.flush();

    verify(test.collector, timeout(5_000)).collectMetrics();
    verify(test.sender, never()).send(PAYLOAD);
  }

  @Test
  void collectionAndTransportExceptionsCompleteFalse() {
    drainMetricsTelemetry();
    TestService collectionFailure = service(PAYLOAD);
    when(collectionFailure.collector.collectMetrics()).thenThrow(new IllegalStateException("boom"));

    assertFalse(collectionFailure.service.shutdown().join(5, SECONDS).isSuccess());

    TestService transportFailure = service(PAYLOAD);
    when(transportFailure.sender.send(PAYLOAD)).thenThrow(new IllegalStateException("boom"));

    assertFalse(transportFailure.service.shutdown().join(5, SECONDS).isSuccess());

    Map<String, OtlpTelemetry.OtlpMetric> metrics = drainMetricsTelemetry();
    assertEquals(1L, metrics.get("otel.metrics_export_attempts").value);
    assertEquals(1L, metrics.get("otel.metrics_export_failures").value);
  }

  @Test
  void shutdownDoesNotCompleteBeforeTransport() throws Exception {
    TestService test = service(PAYLOAD);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(test.sender.send(PAYLOAD))
        .thenAnswer(
            ignored -> {
              entered.countDown();
              assertTrue(release.await(5, SECONDS));
              return success(200);
            });

    CompletableResultCode result = test.service.shutdown();

    assertTrue(entered.await(5, SECONDS));
    assertFalse(result.isDone());
    release.countDown();
    assertTrue(result.join(5, SECONDS).isSuccess());
  }

  @Test
  void concurrentFlushesAreSerialized() throws Exception {
    TestService test = service(PAYLOAD);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(test.collector.collectMetrics())
        .thenAnswer(
            ignored -> {
              int count = active.incrementAndGet();
              maximum.accumulateAndGet(count, Math::max);
              firstEntered.countDown();
              assertTrue(release.await(5, SECONDS));
              active.decrementAndGet();
              return PAYLOAD;
            });
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));

    test.service.flush();
    assertTrue(firstEntered.await(5, SECONDS));
    test.service.flush();
    release.countDown();

    assertTrue(test.service.shutdown().join(5, SECONDS).isSuccess());
    assertEquals(1, maximum.get());
    verify(test.sender, times(3)).send(PAYLOAD);
  }

  @Test
  void shutdownFinalExportsClosesResourcesAndIsIdempotent() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));

    CompletableResultCode first = test.service.shutdown();
    CompletableResultCode second = test.service.shutdown();

    assertTrue(first.join(5, SECONDS).isSuccess());
    assertTrue(second.join(5, SECONDS).isSuccess());
    assertNotSame(first, second);
    verify(test.collector).collectMetrics();
    verify(test.sender).send(PAYLOAD);
    verify(test.sender).shutdown();
    assertTrue(test.executor.isShutdown());
    assertTrue(test.executor.awaitTermination(5, SECONDS));
    test.service.flush();
    verify(test.collector).collectMetrics();
  }

  @Test
  void shutdownClosesResourcesWhenFinalExportFails() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(failed(500));

    assertFalse(test.service.shutdown().join(5, SECONDS).isSuccess());

    verify(test.sender).shutdown();
    assertTrue(test.executor.isShutdown());
    assertTrue(test.executor.awaitTermination(5, SECONDS));
  }

  @Test
  void shutdownReportsSenderCloseFailure() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));
    doThrow(new IllegalStateException("boom")).when(test.sender).shutdown();

    assertFalse(test.service.shutdown().join(5, SECONDS).isSuccess());

    verify(test.sender).shutdown();
    assertTrue(test.executor.awaitTermination(5, SECONDS));
  }

  @Test
  void rejectedLifecycleOperationsFailAndCloseSender() {
    TestService test = service(PAYLOAD);
    test.executor.shutdown();

    test.service.flush();
    assertFalse(test.service.shutdown().join(5, SECONDS).isSuccess());

    verify(test.collector, never()).collectMetrics();
    verify(test.sender).shutdown();
  }

  @Test
  void executorFailuresCompleteShutdownResult() {
    ScheduledExecutorService submissionFailure = mock(ScheduledExecutorService.class);
    OtlpSender sender = mock(OtlpSender.class);
    doThrow(new IllegalStateException("boom")).when(submissionFailure).execute(any(Runnable.class));
    OtlpMetricsService service =
        new OtlpMetricsService(submissionFailure, mock(OtlpMetricsCollector.class), sender, 10_000);

    CompletableResultCode failedSubmission = service.shutdown();
    CompletableResultCode repeatedSubmission = service.shutdown();
    assertTrue(failedSubmission.isDone());
    assertFalse(failedSubmission.isSuccess());
    assertTrue(repeatedSubmission.isDone());
    assertFalse(repeatedSubmission.isSuccess());
    verify(sender).shutdown();
    verify(submissionFailure).shutdown();

    ScheduledExecutorService cleanupFailure = mock(ScheduledExecutorService.class);
    doThrow(new SecurityException("boom")).when(cleanupFailure).shutdown();
    OtlpMetricsService unavailable = new OtlpMetricsService(cleanupFailure, null, null, 10_000);

    CompletableResultCode failedCleanup = unavailable.shutdown();
    CompletableResultCode repeatedCleanup = unavailable.shutdown();
    assertTrue(failedCleanup.isDone());
    assertFalse(failedCleanup.isSuccess());
    assertTrue(repeatedCleanup.isDone());
    assertFalse(repeatedCleanup.isSuccess());
  }

  @Test
  void scheduledExportCancellationFailureCompletesShutdownResult() {
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> scheduledExport = mock(ScheduledFuture.class);
    OtlpMetricsCollector collector = mock(OtlpMetricsCollector.class);
    OtlpSender sender = mock(OtlpSender.class);
    doReturn(scheduledExport)
        .when(executor)
        .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
    when(collector.collectMetrics()).thenReturn(OtlpPayload.EMPTY);
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(0)).run();
              return null;
            })
        .when(executor)
        .execute(any(Runnable.class));
    doThrow(new IllegalStateException("boom")).when(scheduledExport).cancel(false);
    OtlpMetricsService service = new OtlpMetricsService(executor, collector, sender, 10_000);
    service.start();

    CompletableResultCode failedCancellation = service.shutdown();
    CompletableResultCode repeatedCancellation = service.shutdown();

    assertTrue(failedCancellation.isDone());
    assertFalse(failedCancellation.isSuccess());
    assertTrue(repeatedCancellation.isDone());
    assertFalse(repeatedCancellation.isSuccess());
    verify(collector).collectMetrics();
    verify(sender).shutdown();
    verify(executor).shutdown();
  }

  @Test
  void lifecycleSubmissionsDisableAsyncPropagation() {
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.isAsyncPropagationEnabled()).thenReturn(true);
    AgentTracer.forceRegister(tracer);
    ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
    OtlpMetricsService service =
        new OtlpMetricsService(
            executor, mock(OtlpMetricsCollector.class), mock(OtlpSender.class), 10_000);

    service.flush();
    service.shutdown();

    InOrder calls = inOrder(tracer, executor);
    for (int i = 0; i < 2; i++) {
      calls.verify(tracer).isAsyncPropagationEnabled();
      calls.verify(tracer).setAsyncPropagationEnabled(false);
      calls.verify(executor).execute(any(Runnable.class));
      calls.verify(tracer).setAsyncPropagationEnabled(true);
    }
  }

  @Test
  void unavailablePipelineTreatsShutdownAsSuccessfulNoopAndStopsExecutor() throws Exception {
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    executors.add(executor);
    OtlpMetricsService service = new OtlpMetricsService(executor, null, null, 10_000);

    service.flush();
    assertTrue(service.shutdown().join(5, SECONDS).isSuccess());
    assertTrue(executor.awaitTermination(5, SECONDS));
  }

  @Test
  void concurrentShutdownWaitsForInflightFlushAndCompletesAllViews() throws Exception {
    TestService test = service(PAYLOAD);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(test.sender.send(PAYLOAD))
        .thenAnswer(
            ignored -> {
              entered.countDown();
              assertTrue(release.await(5, SECONDS));
              return success(200);
            });

    test.service.flush();
    assertTrue(entered.await(5, SECONDS));
    CompletableResultCode shutdown = test.service.shutdown();
    assertFalse(shutdown.isDone());
    CompletableResultCode throwing = test.service.shutdown();
    throwing.whenComplete(
        () -> {
          throw new IllegalStateException("boom");
        });
    CompletableResultCode unaffected = test.service.shutdown();
    assertNotSame(shutdown, throwing);
    shutdown.fail();
    release.countDown();

    assertFalse(shutdown.join(5, SECONDS).isSuccess());
    assertTrue(throwing.join(5, SECONDS).isSuccess());
    assertTrue(unaffected.join(5, SECONDS).isSuccess());
    assertTrue(test.service.shutdown().join(5, SECONDS).isSuccess());
    verify(test.sender, times(2)).send(PAYLOAD);
    verify(test.sender).shutdown();
  }

  private TestService service(OtlpPayload payload) {
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    executors.add(executor);
    OtlpMetricsCollector collector = mock(OtlpMetricsCollector.class);
    OtlpSender sender = mock(OtlpSender.class);
    when(collector.collectMetrics()).thenReturn(payload);
    return new TestService(
        new OtlpMetricsService(executor, collector, sender, 10_000), executor, collector, sender);
  }

  private static Map<String, OtlpTelemetry.OtlpMetric> drainMetricsTelemetry() {
    Map<String, OtlpTelemetry.OtlpMetric> byName = new HashMap<>();
    OtlpTelemetry.getInstance().prepareMetrics();
    for (OtlpTelemetry.OtlpMetric metric : OtlpTelemetry.getInstance().drain()) {
      if (metric.metricName.startsWith("otel.metrics_")) {
        byName.put(metric.metricName, metric);
      }
    }
    return byName;
  }

  private static final class TestService {
    private final OtlpMetricsService service;
    private final ScheduledExecutorService executor;
    private final OtlpMetricsCollector collector;
    private final OtlpSender sender;

    private TestService(
        OtlpMetricsService service,
        ScheduledExecutorService executor,
        OtlpMetricsCollector collector,
        OtlpSender sender) {
      this.service = service;
      this.executor = executor;
      this.collector = collector;
      this.sender = sender;
    }
  }
}
