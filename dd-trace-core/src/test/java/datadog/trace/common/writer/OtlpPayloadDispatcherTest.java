package datadog.trace.common.writer;

import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.telemetry.OtlpTelemetry;
import datadog.trace.core.CoreSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.core.otlp.trace.OtlpTraceCollector;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OtlpPayloadDispatcherTest {
  @Mock OtlpSender sender;

  TestCollector collector = new TestCollector();

  @BeforeEach
  void stubSuccessfulSend() {
    lenient().when(sender.send(any())).thenReturn(RemoteApi.Response.success(200));
    OtlpTelemetry.getInstance().prepareMetrics();
    OtlpTelemetry.getInstance().drain();
  }

  @Test
  void sampledTraceForwardsAllSpans() {
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);
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
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    dispatcher.addTrace(Arrays.asList(droppedSpan(), droppedSpan()));
    assertEquals(collector.spansToExport, emptyList());
    dispatcher.flush();

    verifyNoInteractions(sender);
  }

  @Test
  void unsetPriorityTraceWithoutSingleSpanSamplingForwardsNothing() {
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    dispatcher.addTrace(Arrays.asList(unsetSpan(), unsetSpan()));
    assertEquals(collector.spansToExport, emptyList());
    dispatcher.flush();

    verifyNoInteractions(sender);
  }

  @Test
  void droppedTraceWithSingleSpanSampledForwardsOnlyThoseSpans() {
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);
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
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    dispatcher.addTrace(emptyList());
    dispatcher.flush();

    verifyNoInteractions(sender);
  }

  @Test
  void flushRecordsSuccessfulExportTelemetry() {
    RemoteApi.Response response = RemoteApi.Response.success(200);
    when(sender.send(any())).thenReturn(response);
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

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
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

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
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    assertTrue(dispatcher.getApis().isEmpty());
  }

  @Test
  void onDroppedTraceDoesNothing() {
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    dispatcher.onDroppedTrace(5);
    dispatcher.flush();

    verifyNoInteractions(sender);
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

  /** Test collector that creates payloads whose size equals the number of exported spans. */
  private static class TestCollector extends OtlpTraceCollector {
    final List<CoreSpan<?>> spansToExport = new ArrayList<>();

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
      if (spansToExport.isEmpty()) {
        return OtlpPayload.EMPTY;
      }
      try {
        // number of bytes returned represents the number of exported spans
        int contentLength = spansToExport.size();
        return new OtlpPayload(ByteBuffer.allocate(contentLength), "application/octet-stream");
      } finally {
        spansToExport.clear();
      }
    }
  }
}
