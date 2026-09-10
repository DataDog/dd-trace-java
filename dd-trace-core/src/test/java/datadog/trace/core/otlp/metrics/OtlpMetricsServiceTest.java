package datadog.trace.core.otlp.metrics;

import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_ENDPOINT;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_PROTOCOL;
import static datadog.trace.common.writer.RemoteApi.Response.failed;
import static datadog.trace.common.writer.RemoteApi.Response.success;
import static datadog.trace.util.AgentThreadFactory.AgentThread.OTLP_METRICS_EXPORTER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.CompletableResultCode;
import datadog.trace.api.Config;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.util.AgentTaskScheduler;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class OtlpMetricsServiceTest {
  private static final OtlpPayload PAYLOAD =
      new OtlpPayload(ByteBuffer.wrap(new byte[] {1}), OtlpPayload.PROTOBUF_CONTENT_TYPE);
  private final List<AgentTaskScheduler> schedulers = new ArrayList<>();
  private final AgentTracer.TracerAPI originalTracer = AgentTracer.get();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(scheduler -> scheduler.shutdown(0, MILLISECONDS));
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
    assertTrue(test.scheduler.isShutdown());
    test.service.flush();
    verify(test.collector).collectMetrics();
  }

  @Test
  void shutdownClosesResourcesWhenFinalExportFails() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(failed(500));

    assertFalse(test.service.shutdown().join(5, SECONDS).isSuccess());

    verify(test.sender).shutdown();
    assertTrue(test.scheduler.isShutdown());
  }

  @Test
  void shutdownReportsSenderCloseFailure() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));
    doThrow(new IllegalStateException("boom")).when(test.sender).shutdown();

    assertFalse(test.service.shutdown().join(5, SECONDS).isSuccess());

    verify(test.sender).shutdown();
    assertTrue(test.scheduler.isShutdown());
  }

  @Test
  void rejectedLifecycleOperationsFailAndCloseSender() {
    AgentTaskScheduler scheduler = mock(AgentTaskScheduler.class);
    doThrow(new IllegalStateException("boom")).when(scheduler).execute(any(Runnable.class));
    OtlpMetricsCollector collector = mock(OtlpMetricsCollector.class);
    OtlpSender sender = mock(OtlpSender.class);
    OtlpMetricsService service = new OtlpMetricsService(scheduler, collector, sender, 10_000);

    service.flush();
    assertFalse(service.shutdown().join(5, SECONDS).isSuccess());

    verify(collector, never()).collectMetrics();
    verify(sender).shutdown();
  }

  @Test
  void executorFailuresCompleteShutdownResult() {
    AgentTaskScheduler submissionFailure = mock(AgentTaskScheduler.class);
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
  }

  @Test
  void lifecycleSubmissionsDisableAsyncPropagation() {
    AgentTracer.TracerAPI tracer = mock(AgentTracer.TracerAPI.class);
    when(tracer.isAsyncPropagationEnabled()).thenReturn(true);
    AgentTracer.forceRegister(tracer);
    AgentTaskScheduler scheduler = mock(AgentTaskScheduler.class);
    OtlpMetricsService service =
        new OtlpMetricsService(
            scheduler, mock(OtlpMetricsCollector.class), mock(OtlpSender.class), 10_000);

    service.flush();
    service.shutdown();

    InOrder calls = inOrder(tracer, scheduler);
    for (int i = 0; i < 2; i++) {
      calls.verify(tracer).isAsyncPropagationEnabled();
      calls.verify(tracer).setAsyncPropagationEnabled(false);
      calls.verify(scheduler).execute(any(Runnable.class));
      calls.verify(tracer).setAsyncPropagationEnabled(true);
    }
  }

  @Test
  void unavailablePipelineTreatsShutdownAsSuccessfulNoopAndStopsScheduler() {
    AgentTaskScheduler scheduler = new AgentTaskScheduler(OTLP_METRICS_EXPORTER);
    schedulers.add(scheduler);
    OtlpMetricsService service = new OtlpMetricsService(scheduler, null, null, 10_000);

    service.flush();
    assertTrue(service.shutdown().join(5, SECONDS).isSuccess());
    assertTrue(scheduler.isShutdown());
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

  @Test
  void blockedCallbackOnOneShutdownViewDoesNotDelayAnotherView() throws Exception {
    TestService test = service(PAYLOAD);
    CountDownLatch exportEntered = new CountDownLatch(1);
    CountDownLatch releaseExport = new CountDownLatch(1);
    CountDownLatch callbackEntered = new CountDownLatch(1);
    CountDownLatch releaseCallback = new CountDownLatch(1);
    when(test.sender.send(PAYLOAD))
        .thenAnswer(
            ignored -> {
              exportEntered.countDown();
              assertTrue(releaseExport.await(5, SECONDS));
              return success(200);
            });

    CompletableResultCode blocking = test.service.shutdown();
    blocking.whenComplete(
        () -> {
          callbackEntered.countDown();
          try {
            assertTrue(releaseCallback.await(5, SECONDS));
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        });
    CompletableResultCode unaffected = test.service.shutdown();

    assertTrue(exportEntered.await(5, SECONDS));
    releaseExport.countDown();
    assertTrue(callbackEntered.await(5, SECONDS));
    try {
      assertTrue(unaffected.join(1, SECONDS).isSuccess());
    } finally {
      releaseCallback.countDown();
    }
    assertTrue(blocking.join(5, SECONDS).isSuccess());
  }

  private TestService service(OtlpPayload payload) {
    AgentTaskScheduler scheduler = new AgentTaskScheduler(OTLP_METRICS_EXPORTER);
    schedulers.add(scheduler);
    OtlpMetricsCollector collector = mock(OtlpMetricsCollector.class);
    OtlpSender sender = mock(OtlpSender.class);
    when(collector.collectMetrics()).thenReturn(payload);
    return new TestService(
        new OtlpMetricsService(scheduler, collector, sender, 10_000), scheduler, collector, sender);
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
    private final AgentTaskScheduler scheduler;
    private final OtlpMetricsCollector collector;
    private final OtlpSender sender;

    private TestService(
        OtlpMetricsService service,
        AgentTaskScheduler scheduler,
        OtlpMetricsCollector collector,
        OtlpSender sender) {
      this.service = service;
      this.scheduler = scheduler;
      this.collector = collector;
      this.sender = sender;
    }
  }
}
