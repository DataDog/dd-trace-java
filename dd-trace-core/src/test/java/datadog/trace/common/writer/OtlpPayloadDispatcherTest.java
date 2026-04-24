package datadog.trace.common.writer;

import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.core.CoreSpan;
import datadog.trace.core.otlp.common.OtlpPayload;
import datadog.trace.core.otlp.common.OtlpSender;
import datadog.trace.core.otlp.trace.OtlpTraceCollector;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OtlpPayloadDispatcherTest {

  @Mock OtlpSender sender;
  @Mock OtlpTraceCollector collector;

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void sampledTraceForwardsAllSpans() {
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);
    List<CoreSpan<?>> trace = Arrays.asList(sampledSpan(), sampledSpan());

    dispatcher.addTrace(trace);

    ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
    verify(collector).addTrace(captor.capture());
    assertEquals(trace, captor.getValue());
    verifyNoInteractions(sender);
  }

  @Test
  void droppedTraceWithoutSingleSpanSamplingForwardsNothing() {
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    dispatcher.addTrace(Arrays.asList(droppedSpan(), droppedSpan()));

    verifyNoInteractions(collector, sender);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void droppedTraceWithSingleSpanSampledForwardsOnlyThoseSpans() {
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);
    CoreSpan<?> keep = singleSpanSampledSpan();
    CoreSpan<?> drop1 = droppedSpan();
    CoreSpan<?> drop2 = droppedSpan();

    dispatcher.addTrace(Arrays.asList(drop1, keep, drop2));

    ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
    verify(collector).addTrace(captor.capture());
    assertEquals(Collections.singletonList(keep), captor.getValue());
  }

  @Test
  void emptyTraceForwardsNothing() {
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    dispatcher.addTrace(Collections.emptyList());

    verifyNoInteractions(collector, sender);
  }

  @Test
  void flushSendsNonEmptyPayload() {
    Deque<byte[]> chunks = new ArrayDeque<>();
    chunks.add(new byte[] {1, 2, 3});
    OtlpPayload payload = new OtlpPayload(chunks, 3, "application/x-protobuf");
    when(collector.collectTraces()).thenReturn(payload);
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    dispatcher.flush();

    verify(sender).send(payload);
  }

  @Test
  void flushSkipsEmptyPayload() {
    when(collector.collectTraces()).thenReturn(OtlpPayload.EMPTY);
    OtlpPayloadDispatcher dispatcher = new OtlpPayloadDispatcher(sender, collector);

    dispatcher.flush();

    verifyNoInteractions(sender);
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

    verifyNoInteractions(collector, sender);
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
}
