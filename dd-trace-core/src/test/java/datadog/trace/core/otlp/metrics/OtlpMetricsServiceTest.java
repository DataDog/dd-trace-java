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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.bootstrap.otel.metrics.data.OtelMetricRegistry;
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

class OtlpMetricsServiceTest {
  private static final OtlpPayload PAYLOAD =
      new OtlpPayload(ByteBuffer.wrap(new byte[] {1}), OtlpPayload.PROTOBUF_CONTENT_TYPE);
  private final List<AgentTaskScheduler> schedulers = new ArrayList<>();

  @AfterEach
  void stopSchedulers() {
    schedulers.forEach(scheduler -> scheduler.shutdown(0, MILLISECONDS));
    OtelMetricRegistry.INSTANCE.registerShutdownHook(null);
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
    service.shutdown();
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
  void collectionAndTransportExceptionsAreRecordedAsFailures() {
    drainMetricsTelemetry();
    TestService collectionFailure = service(PAYLOAD);
    when(collectionFailure.collector.collectMetrics()).thenThrow(new IllegalStateException("boom"));

    assertTrue(collectionFailure.service.exportThenShutdown(5, SECONDS));

    TestService transportFailure = service(PAYLOAD);
    when(transportFailure.sender.send(PAYLOAD)).thenThrow(new IllegalStateException("boom"));

    assertTrue(transportFailure.service.exportThenShutdown(5, SECONDS));

    Map<String, OtlpTelemetry.OtlpMetric> metrics = drainMetricsTelemetry();
    assertEquals(1L, metrics.get("otel.metrics_export_attempts").value);
    assertEquals(1L, metrics.get("otel.metrics_export_failures").value);
  }

  @Test
  void startRegistersShutdownHook() {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));

    OtelMetricRegistry.INSTANCE.shutdown();

    verify(test.sender).send(PAYLOAD);
    verify(test.sender).shutdown();
  }

  @Test
  void exportThenShutdownDoesNotCompleteBeforeTransport() throws Exception {
    TestService test = blockingService();

    assertFalse(test.service.exportThenShutdown(100, MILLISECONDS));

    assertTrue(test.sendEntered.await(5, SECONDS));
    test.releaseSend.countDown();
    assertTrue(test.service.exportThenShutdown(5, SECONDS));
  }

  @Test
  void interruptedExportThenShutdownRestoresInterruptStatus() throws Exception {
    TestService test = blockingService();
    assertFalse(test.service.exportThenShutdown(100, MILLISECONDS));
    assertTrue(test.sendEntered.await(5, SECONDS));

    Thread.currentThread().interrupt();
    try {
      assertFalse(test.service.exportThenShutdown(5, SECONDS));
      assertTrue(Thread.interrupted());
    } finally {
      test.releaseSend.countDown();
    }
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

    assertTrue(test.service.exportThenShutdown(5, SECONDS));
    assertEquals(1, maximum.get());
    verify(test.sender, times(3)).send(PAYLOAD);
  }

  @Test
  void exportThenShutdownClosesResourcesAndIsIdempotent() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));

    assertTrue(test.service.exportThenShutdown(5, SECONDS));
    assertTrue(test.service.exportThenShutdown(5, SECONDS));

    verify(test.collector).collectMetrics();
    verify(test.sender).send(PAYLOAD);
    verify(test.sender).shutdown();
    test.service.flush();
    verify(test.collector).collectMetrics();
  }

  @Test
  void exportThenShutdownClosesResourcesWhenFinalExportFails() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(failed(500));

    assertTrue(test.service.exportThenShutdown(5, SECONDS));

    verify(test.sender).shutdown();
  }

  @Test
  void exportThenShutdownSwallowsSenderCloseFailure() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));
    doThrow(new IllegalStateException("boom")).when(test.sender).shutdown();

    assertTrue(test.service.exportThenShutdown(5, SECONDS));

    verify(test.sender).shutdown();
  }

  @Test
  void flushIsSafeWithoutSender() {
    OtlpMetricsService service =
        new OtlpMetricsService(mock(AgentTaskScheduler.class), null, null, 10_000);

    service.flush();
  }

  @Test
  void shutdownCancelsScheduledExportAndClosesSenderWithoutStoppingScheduler() {
    TestService test = service(PAYLOAD);

    test.service.shutdown();

    verify(test.sender).shutdown();
    assertFalse(test.scheduler.isShutdown());
  }

  @Test
  void shutdownSwallowsSenderCloseFailure() {
    TestService test = service(PAYLOAD);
    doThrow(new IllegalStateException("boom")).when(test.sender).shutdown();

    test.service.shutdown();

    verify(test.sender).shutdown();
  }

  @Test
  void shutdownIsSafeWithoutSenderOrScheduledTask() {
    OtlpMetricsService service =
        new OtlpMetricsService(mock(AgentTaskScheduler.class), null, null, 10_000);

    service.shutdown();
  }

  @Test
  void exportThenShutdownWaitsForInflightFlush() throws Exception {
    TestService test = blockingService();

    test.service.flush();
    assertTrue(test.sendEntered.await(5, SECONDS));
    assertFalse(test.service.exportThenShutdown(100, MILLISECONDS));
    test.releaseSend.countDown();

    assertTrue(test.service.exportThenShutdown(5, SECONDS));
    verify(test.sender, times(2)).send(PAYLOAD);
    verify(test.sender).shutdown();
  }

  private TestService blockingService() {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD))
        .thenAnswer(
            ignored -> {
              test.sendEntered.countDown();
              assertTrue(test.releaseSend.await(5, SECONDS));
              return success(200);
            });
    return test;
  }

  private TestService service(OtlpPayload payload) {
    AgentTaskScheduler scheduler = new AgentTaskScheduler(OTLP_METRICS_EXPORTER);
    schedulers.add(scheduler);
    OtlpMetricsCollector collector = mock(OtlpMetricsCollector.class);
    OtlpSender sender = mock(OtlpSender.class);
    when(collector.collectMetrics()).thenReturn(payload);
    OtlpMetricsService service = new OtlpMetricsService(scheduler, collector, sender, 60_000);
    service.start();
    return new TestService(service, scheduler, collector, sender);
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
    private final CountDownLatch sendEntered = new CountDownLatch(1);
    private final CountDownLatch releaseSend = new CountDownLatch(1);

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
