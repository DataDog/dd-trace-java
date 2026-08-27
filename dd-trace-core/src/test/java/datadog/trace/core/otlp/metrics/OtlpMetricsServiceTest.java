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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OtlpMetricsServiceTest {
  private static final OtlpPayload PAYLOAD =
      new OtlpPayload(ByteBuffer.wrap(new byte[] {1}), OtlpPayload.PROTOBUF_CONTENT_TYPE);
  private final List<ScheduledExecutorService> executors = new ArrayList<>();

  @AfterEach
  void stopExecutors() {
    executors.forEach(ScheduledExecutorService::shutdownNow);
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
    service.shutdown().join();
  }

  @Test
  void forceFlushCompletesWithTransportResult() {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200), failed(500));

    assertTrue(test.service.forceFlush().join());
    assertFalse(test.service.forceFlush().join());
  }

  @Test
  void emptyFlushSucceedsWithoutTransport() {
    TestService test = service(OtlpPayload.EMPTY);

    assertTrue(test.service.forceFlush().join());

    verify(test.sender, never()).send(PAYLOAD);
  }

  @Test
  void collectionAndTransportExceptionsCompleteFalse() {
    drainMetricsTelemetry();
    TestService collectionFailure = service(PAYLOAD);
    when(collectionFailure.collector.collectMetrics()).thenThrow(new IllegalStateException("boom"));

    assertFalse(collectionFailure.service.forceFlush().join());

    TestService transportFailure = service(PAYLOAD);
    when(transportFailure.sender.send(PAYLOAD)).thenThrow(new IllegalStateException("boom"));

    assertFalse(transportFailure.service.forceFlush().join());

    Map<String, OtlpTelemetry.OtlpMetric> metrics = drainMetricsTelemetry();
    assertEquals(1L, metrics.get("otel.metrics_export_attempts").value);
    assertEquals(1L, metrics.get("otel.metrics_export_failures").value);
  }

  @Test
  void forceFlushDoesNotCompleteBeforeTransport() throws Exception {
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

    CompletableFuture<Boolean> result = test.service.forceFlush();

    assertTrue(entered.await(5, SECONDS));
    assertFalse(result.isDone());
    release.countDown();
    assertTrue(result.get(5, SECONDS));
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

    CompletableFuture<Boolean> first = test.service.forceFlush();
    assertTrue(firstEntered.await(5, SECONDS));
    CompletableFuture<Boolean> second = test.service.forceFlush();
    release.countDown();

    assertTrue(first.get(5, SECONDS));
    assertTrue(second.get(5, SECONDS));
    assertEquals(1, maximum.get());
  }

  @Test
  void shutdownFinalExportsClosesResourcesAndIsIdempotent() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(success(200));

    CompletableFuture<Boolean> first = test.service.shutdown();
    CompletableFuture<Boolean> second = test.service.shutdown();

    assertTrue(first.join());
    assertTrue(second.join());
    assertNotSame(first, second);
    verify(test.collector).collectMetrics();
    verify(test.sender).send(PAYLOAD);
    verify(test.sender).shutdown();
    assertTrue(test.executor.isShutdown());
    assertTrue(test.executor.awaitTermination(5, SECONDS));
    assertFalse(test.service.forceFlush().join());
  }

  @Test
  void shutdownClosesResourcesWhenFinalExportFails() throws Exception {
    TestService test = service(PAYLOAD);
    when(test.sender.send(PAYLOAD)).thenReturn(failed(500));

    assertFalse(test.service.shutdown().join());

    verify(test.sender).shutdown();
    assertTrue(test.executor.isShutdown());
    assertTrue(test.executor.awaitTermination(5, SECONDS));
  }

  @Test
  void concurrentShutdownWaitsForInflightFlushAndExportsOnce() throws Exception {
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

    CompletableFuture<Boolean> flush = test.service.forceFlush();
    assertTrue(entered.await(5, SECONDS));
    CompletableFuture<Boolean> shutdown = test.service.shutdown();
    assertFalse(shutdown.isDone());
    CompletableFuture<Boolean> repeated = test.service.shutdown();
    assertNotSame(shutdown, repeated);
    shutdown.complete(false);
    release.countDown();

    assertTrue(flush.get(5, SECONDS));
    assertFalse(shutdown.get(5, SECONDS));
    assertTrue(repeated.get(5, SECONDS));
    assertTrue(test.service.shutdown().get(5, SECONDS));
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
