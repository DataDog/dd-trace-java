package datadog.trace.api.propagation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class W3CTraceParentTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("buildProducesCorrectFormatArguments")
  void buildProducesCorrectFormat(
      String scenario, DDTraceId traceId, long spanId, boolean isSampled, String expected) {
    assertEquals(expected, W3CTraceParent.from(traceId, spanId, isSampled));
  }

  static Stream<Arguments> buildProducesCorrectFormatArguments() {
    return Stream.of(
        arguments(
            "sampled",
            DDTraceId.from(1),
            2L,
            true,
            "00-00000000000000000000000000000001-0000000000000002-01"),
        arguments(
            "not sampled",
            DDTraceId.from(1),
            2L,
            false,
            "00-00000000000000000000000000000001-0000000000000002-00"),
        arguments(
            "W3C example",
            DDTraceId.fromHex("0af7651916cd43dd8448eb211c80319c"),
            0x00f067aa0ba902b7L,
            true,
            "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01"),
        arguments(
            "Long.MAX_VALUE ids",
            DDTraceId.from(Long.MAX_VALUE),
            Long.MAX_VALUE,
            true,
            "00-00000000000000007fffffffffffffff-7fffffffffffffff-01"));
  }

  @Test
  void buildMatchesW3CTraceparentFormat() {
    // W3C format: version-traceId(32 hex)-spanId(16 hex)-flags(2 hex)
    String result = W3CTraceParent.from(DDTraceId.from(123456789L), 987654321L, true);
    assertTrue(result.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-(00|01)"));
  }

  @Test
  void buildFromSpanSampled() {
    AgentSpan span = mock(AgentSpan.class);
    AgentSpanContext context = mock(AgentSpanContext.class);
    DDTraceId traceId = DDTraceId.fromHex("0af7651916cd43dd8448eb211c80319c");
    long spanId = 0x00f067aa0ba902b7L;

    when(span.getTraceId()).thenReturn(traceId);
    when(span.getSpanId()).thenReturn(spanId);
    when(span.spanContext()).thenReturn(context);
    when(context.getSamplingPriority()).thenReturn(1);

    assertEquals(
        "00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01", W3CTraceParent.from(span));
  }

  @Test
  void buildFromSpanNotSampled() {
    AgentSpan span = mock(AgentSpan.class);
    AgentSpanContext context = mock(AgentSpanContext.class);

    when(span.getTraceId()).thenReturn(DDTraceId.from(1));
    when(span.getSpanId()).thenReturn(2L);
    when(span.spanContext()).thenReturn(context);
    when(context.getSamplingPriority()).thenReturn(0);

    assertEquals(
        "00-00000000000000000000000000000001-0000000000000002-00", W3CTraceParent.from(span));
  }
}
