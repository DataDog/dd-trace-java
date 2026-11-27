package datadog.trace.common.writer;

import static datadog.trace.agent.test.utils.OtelMetricCollector.collect;
import static datadog.trace.api.config.OtlpConfig.Protocol.HTTP_PROTOBUF;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;
import static datadog.trace.core.monitor.HealthMetrics.NO_OP;
import static datadog.trace.core.monitor.OtelExporterId.Signal.SPAN;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.metrics.api.Histograms;
import datadog.metrics.impl.DDSketchHistograms;
import datadog.trace.agent.test.utils.OtelMetricCollector;
import datadog.trace.agent.test.utils.OtelMetricCollector.DataPointEntry;
import datadog.trace.api.Config;
import datadog.trace.api.config.OtlpConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.bootstrap.otlp.metrics.OtlpHistogramPoint;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.monitor.ExportRecorder;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.monitor.OtelExporterId;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.core.otlp.sdk.OtelSdkSelfMonitoring;
import datadog.trace.core.otlp.trace.OtlpTraceCollector;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tabletest.junit.TableTest;

@ExtendWith(MockitoExtension.class)
class OtlpPayloadDispatcherTest {
  @Mock OtlpSender sender;

  private static final String INFLIGHT = "otel.sdk.exporter.span.inflight";
  private static final String EXPORTED = "otel.sdk.exporter.span.exported";
  private static final String DURATION = "otel.sdk.exporter.operation.duration";

  TestCollector collector = new TestCollector();

  @BeforeAll
  static void registerHistogramFactory() {
    Histograms.register(DDSketchHistograms.FACTORY);
  }

  @BeforeEach
  void stubSuccessfulSend() {
    lenient().when(sender.send(any())).thenReturn(RemoteApi.Response.success(200));
    OtlpTelemetry.getInstance().prepareMetrics();
    OtlpTelemetry.getInstance().drain();
  }

  @Test
  void sampledTraceForwardsAllSpans() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);
    List<CoreSpan<?>> trace = Arrays.asList(sampledSpan(), sampledSpan());

    dispatcher.addTrace(trace);
    assertEquals(collector.spansToExport, trace);
    dispatcher.flush();

    // expect two spans to be exported
    ArgumentCaptor<OtlpPayload> captor = ArgumentCaptor.forClass(OtlpPayload.class);
    verify(sender).send(captor.capture());
    assertEquals(2 /*spans*/, captor.getValue().getContentLength());
  }

  @Test
  void droppedTraceWithoutSingleSpanSamplingForwardsNothing() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);

    dispatcher.addTrace(Arrays.asList(droppedSpan(), droppedSpan()));
    assertEquals(collector.spansToExport, emptyList());
    dispatcher.flush();

    verify(sender, never()).send(any());
  }

  @Test
  void unsetPriorityTraceWithoutSingleSpanSamplingForwardsNothing() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);

    dispatcher.addTrace(Arrays.asList(unsetSpan(), unsetSpan()));
    assertEquals(collector.spansToExport, emptyList());
    dispatcher.flush();

    verify(sender, never()).send(any());
  }

  @Test
  void droppedTraceWithSingleSpanSampledForwardsOnlyThoseSpans() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);
    CoreSpan<?> keep = singleSpanSampledSpan();
    CoreSpan<?> drop1 = droppedSpan();
    CoreSpan<?> drop2 = droppedSpan();

    dispatcher.addTrace(Arrays.asList(drop1, keep, drop2));
    assertEquals(collector.spansToExport, singletonList(keep));
    dispatcher.flush();

    // expect only one span to be exported
    ArgumentCaptor<OtlpPayload> captor = ArgumentCaptor.forClass(OtlpPayload.class);
    verify(sender).send(captor.capture());
    assertEquals(1 /*spans*/, captor.getValue().getContentLength());
  }

  @Test
  void emptyTraceForwardsNothing() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);

    dispatcher.addTrace(emptyList());
    dispatcher.flush();

    verify(sender, never()).send(any());
  }

  @Test
  void largeTraceTriggersProactiveFlushWithoutExplicitFlush() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);
    collector.fakeSizeInBytes = Integer.MAX_VALUE;

    dispatcher.addTrace(singletonList(sampledSpan()));

    // no explicit dispatcher.flush() call
    ArgumentCaptor<OtlpPayload> captor = ArgumentCaptor.forClass(OtlpPayload.class);
    verify(sender).send(captor.capture());
    assertEquals(1 /*spans*/, captor.getValue().getContentLength());
  }

  @Test
  void belowFlushThresholdDoesNotTriggerProactiveFlush() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);
    collector.fakeSizeInBytes = (5 << 20) - 1; // one byte under FLUSH_THRESHOLD_BYTES

    dispatcher.addTrace(singletonList(sampledSpan()));

    verify(sender, never()).send(any());
  }

  @Test
  void atFlushThresholdTriggersProactiveFlush() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);
    collector.fakeSizeInBytes = 5 << 20; // exactly FLUSH_THRESHOLD_BYTES

    dispatcher.addTrace(singletonList(sampledSpan()));

    verify(sender).send(any(OtlpPayload.class));
  }

  @Test
  void flushSwallowsCollectorFailureInsteadOfPropagating() {
    // e.g. a held-back span pushes the payload over the buffer's hard cap only once
    // collectTraces() finalizes it during a scheduled flush, not during addTrace()
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);
    dispatcher.addTrace(singletonList(sampledSpan()));
    collector.throwOnCollect = true;

    dispatcher.flush();

    verify(sender, never()).send(any());
  }

  @Test
  void dispatcherRemainsUsableAfterCollectorFailure() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);
    dispatcher.addTrace(singletonList(sampledSpan()));
    collector.throwOnCollect = true;
    dispatcher.flush();

    collector.throwOnCollect = false;
    dispatcher.addTrace(singletonList(sampledSpan()));
    dispatcher.flush();

    ArgumentCaptor<OtlpPayload> captor = ArgumentCaptor.forClass(OtlpPayload.class);
    verify(sender).send(captor.capture());
    assertEquals(1 /*spans*/, captor.getValue().getContentLength());
  }

  @Test
  void flushRecordsSuccessfulExportTelemetry() {
    RemoteApi.Response response = RemoteApi.Response.success(200);
    when(sender.send(any())).thenReturn(response);
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);

    dispatcher.addTrace(Arrays.asList(sampledSpan(), sampledSpan()));
    dispatcher.flush();

    Map<String, OtlpTelemetry.OtlpMetric> metrics = drainTracesTelemetry();
    assertEquals(2, metrics.size());
    assertEquals(1L, metrics.get("otel.traces_export_attempts").value);
    assertEquals(1L, metrics.get("otel.traces_export_successes").value);
  }

  @Test
  void flushRecordsFailedExportTelemetry() {
    RemoteApi.Response response = RemoteApi.Response.failed(500);
    when(sender.send(any())).thenReturn(response);
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);

    dispatcher.addTrace(Arrays.asList(sampledSpan(), sampledSpan()));
    dispatcher.flush();

    Map<String, OtlpTelemetry.OtlpMetric> metrics = drainTracesTelemetry();
    assertEquals(2, metrics.size());
    assertEquals(1L, metrics.get("otel.traces_export_attempts").value);
    assertEquals(1L, metrics.get("otel.traces_export_failures").value);
  }

  private static Map<String, OtlpTelemetry.OtlpMetric> drainTracesTelemetry() {
    Map<String, OtlpTelemetry.OtlpMetric> byName = new HashMap<>();
    OtlpTelemetry.getInstance().prepareMetrics();
    for (OtlpTelemetry.OtlpMetric metric : OtlpTelemetry.getInstance().drain()) {
      byName.put(metric.metricName, metric);
    }
    return byName;
  }

  @Test
  void getApisIsEmpty() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);

    assertTrue(dispatcher.getApis().isEmpty());
  }

  @Test
  void onDroppedTraceDoesNothing() {
    OtlpPayloadDispatcher dispatcher = dispatcher(HTTP_PROTOBUF, NO_OP);

    dispatcher.onDroppedTrace(5);
    dispatcher.flush();

    verify(sender, never()).send(any());
  }

  // spotless:off
  @TableTest({
      "scenario      | protocol      | type                           | statusKey                   | status",
      "HTTP protobuf | HTTP_PROTOBUF | 'otlp_http_span_exporter'      | 'http.response.status_code' | '200' ",
      "HTTP JSON     | HTTP_JSON     | 'otlp_http_json_span_exporter' | 'http.response.status_code' | '200' ",
      "gRPC          | GRPC          | 'otlp_grpc_span_exporter'      | 'rpc.status_code'           | 'OK'  "
  })
  // spotless:on
  void successfulExportRecordsSpanExporterMetrics(
      OtlpConfig.Protocol protocol, String type, String statusKey, String status) {
    String host = "collector-ok-" + protocol.name().toLowerCase();
    OtlpPayloadDispatcher dispatcher = metricsDispatcher(protocol, host);

    dispatcher.addTrace(Arrays.asList(sampledSpan(), sampledSpan(), droppedSpan()));
    dispatcher.flush();

    OtelMetricCollector metrics = collect();
    assertEquals(0L, onlyPoint(metrics, INFLIGHT, host).value.longValue());
    DataPointEntry exported = onlyPoint(metrics, EXPORTED, host);
    assertEquals(2L, exported.value.longValue()); // the dropped span isn't exported
    assertEquals(type, exported.attrs.get("otel.component.type"));
    assertEquals("4318", exported.attrs.get("server.port"));
    assertFalse(exported.attrs.containsKey("error.type"));
    DataPointEntry duration = onlyPoint(metrics, DURATION, host);
    assertEquals(status, duration.attrs.get(statusKey));
    assertEquals(1.0, ((OtlpHistogramPoint) duration.point).count);
  }

  @Test
  void failedExportRecordsTheStatusCodeAsErrorType() {
    String host = "collector-503";
    when(sender.send(any())).thenReturn(RemoteApi.Response.failed(503));
    OtlpPayloadDispatcher dispatcher = metricsDispatcher(HTTP_PROTOBUF, host);

    dispatcher.addTrace(Arrays.asList(sampledSpan(), sampledSpan()));
    dispatcher.flush();

    OtelMetricCollector metrics = collect();
    DataPointEntry exported = onlyPoint(metrics, EXPORTED, host);
    assertEquals(2L, exported.value.longValue());
    assertEquals("503", exported.attrs.get("error.type"));
    assertEquals("503", onlyPoint(metrics, DURATION, host).attrs.get("http.response.status_code"));
  }

  @Test
  void sendExceptionRecordsTheExceptionAsErrorType() {
    String host = "collector-exception";
    when(sender.send(any())).thenThrow(new IllegalStateException("boom"));
    OtlpPayloadDispatcher dispatcher = metricsDispatcher(HTTP_PROTOBUF, host);

    dispatcher.addTrace(singletonList(sampledSpan()));
    dispatcher.flush(); // the exception is still swallowed

    OtelMetricCollector metrics = collect();
    assertEquals(0L, onlyPoint(metrics, INFLIGHT, host).value.longValue());
    DataPointEntry exported = onlyPoint(metrics, EXPORTED, host);
    assertEquals(1L, exported.value.longValue());
    assertEquals("java.lang.IllegalStateException", exported.attrs.get("error.type"));
  }

  @Test
  void sendErrorRecordsTheFailureAndPropagates() {
    String host = "collector-error";
    when(sender.send(any())).thenThrow(new NoClassDefFoundError("boom"));
    OtlpPayloadDispatcher dispatcher = metricsDispatcher(HTTP_PROTOBUF, host);

    dispatcher.addTrace(singletonList(sampledSpan()));
    assertThrows(NoClassDefFoundError.class, dispatcher::flush);

    OtelMetricCollector metrics = collect();
    assertEquals(0L, onlyPoint(metrics, INFLIGHT, host).value.longValue());
    DataPointEntry exported = onlyPoint(metrics, EXPORTED, host);
    assertEquals(1L, exported.value.longValue());
    assertEquals("java.lang.NoClassDefFoundError", exported.attrs.get("error.type"));
  }

  private OtlpPayloadDispatcher dispatcher(
      OtlpConfig.Protocol protocol, HealthMetrics healthMetrics) {
    return new OtlpPayloadDispatcher(
        sender,
        collector,
        ExportRecorder.create(
            healthMetrics, OtelExporterId.otlp(SPAN, protocol, sender.destination())));
  }

  /**
   * A dispatcher reporting to the OTel sink and sending to {@code host}, which tells its points
   * apart from other tests'.
   */
  private OtlpPayloadDispatcher metricsDispatcher(OtlpConfig.Protocol protocol, String host) {
    when(sender.destination()).thenReturn(HttpUrl.get("http://" + host + ":4318/v1/traces"));
    return dispatcher(protocol, otelSink());
  }

  private static HealthMetrics otelSink() {
    Config config = mock(Config.class);
    when(config.isOtelSdkSelfMonitoringMetricsActive()).thenReturn(true);
    HealthMetrics sink = OtelSdkSelfMonitoring.healthMetrics(config);
    assertNotNull(sink);
    return sink;
  }

  private static DataPointEntry onlyPoint(OtelMetricCollector metrics, String metric, String host) {
    List<DataPointEntry> points =
        metrics.points(metric).stream()
            .filter(point -> host.equals(point.attrs.get("server.address")))
            .collect(toList());
    assertEquals(1, points.size(), metric + " points of " + host);
    return points.get(0);
  }

  private static CoreSpan<?> sampledSpan() {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.samplingPriority()).thenReturn(1);
    return span;
  }

  private static CoreSpan<?> droppedSpan() {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.samplingPriority()).thenReturn(0);
    when(span.getTag(SPAN_SAMPLING_MECHANISM_TAG)).thenReturn(null);
    return span;
  }

  private static CoreSpan<?> singleSpanSampledSpan() {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.samplingPriority()).thenReturn(0);
    when(span.getTag(SPAN_SAMPLING_MECHANISM_TAG)).thenReturn(8);
    return span;
  }

  private static CoreSpan<?> unsetSpan() {
    CoreSpan<?> span = mock(CoreSpan.class);
    when(span.samplingPriority()).thenReturn((int) PrioritySampling.UNSET);
    when(span.getTag(SPAN_SAMPLING_MECHANISM_TAG)).thenReturn(null);
    return span;
  }

  /**
   * Test collector that creates payloads whose size and item count equal the number of exported
   * spans.
   */
  private static class TestCollector extends OtlpTraceCollector {
    final List<CoreSpan<?>> spansToExport = new ArrayList<>();

    // lets tests drive the proactive-flush threshold independently of spansToExport
    int fakeSizeInBytes;

    // lets tests simulate a collectTraces() failure, e.g. a buffer overflow on the held-back span
    boolean throwOnCollect;

    @Override
    public void addTrace(List<? extends CoreSpan<?>> spans) {
      for (CoreSpan<?> span : spans) {
        if (shouldExport(span)) {
          spansToExport.add(span);
        }
      }
    }

    @Override
    public OtlpPayload collectTraces() {
      if (throwOnCollect) {
        // mirrors the real collectors, which always reset state via a finally block
        spansToExport.clear();
        throw new IllegalStateException("simulated buffer overflow");
      }
      if (spansToExport.isEmpty()) {
        return OtlpPayload.EMPTY;
      }
      try {
        // number of bytes returned represents the number of exported spans
        int contentLength = spansToExport.size();
        return new OtlpPayload(
            ByteBuffer.allocate(contentLength), "application/octet-stream", contentLength);
      } finally {
        spansToExport.clear();
      }
    }

    @Override
    public int sizeInBytes() {
      return fakeSizeInBytes;
    }
  }
}
