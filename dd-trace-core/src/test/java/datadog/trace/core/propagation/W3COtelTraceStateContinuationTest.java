package datadog.trace.core.propagation;

import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.TracePropagationStyle.TRACECONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Exercises the {@code ot=} tracestate member through the real {@link W3CHttpCodec} extractor and
 * injector, continuing an inbound trace rather than parsing tracestate or building an {@link
 * ExtractedContext} by hand.
 */
class W3COtelTraceStateContinuationTest extends DDCoreJavaSpecification {

  private static final String TRACE_PARENT =
      "00-00000000000000000000000000000001-123456789abcdef0-01";
  private static final String DATADOG_TRACE_ID = "1";
  private static final String DATADOG_PARENT_ID = "2";
  private static final String OTEL_RANDOM_VALUE = "ef284ace7a91e1";
  private static final String OTEL_THRESHOLD = "e6666666666668";
  private static final String OTEL_MALFORMED_RANDOM_VALUE = "zz";
  private static final String DD_MEMBER = "dd=s:2;p:123456789abcdef0";

  private final HttpCodec.Injector injector = W3CHttpCodec.newInjector(emptyMap());

  @Test
  void roundTripsOtelTraceState() {
    String inboundTracestate = DD_MEMBER + ",ot=rv:" + OTEL_RANDOM_VALUE + ";th:" + OTEL_THRESHOLD;
    String outboundTracestate = continueTraceAndReinject(inboundTracestate);

    assertTrue(outboundTracestate.contains("ot=rv:" + OTEL_RANDOM_VALUE + ";th:" + OTEL_THRESHOLD));
  }

  @Test
  void removesMalformedOtelRandomValueButKeepsThreshold() {
    String inboundTracestate =
        DD_MEMBER + ",ot=rv:" + OTEL_MALFORMED_RANDOM_VALUE + ";th:" + OTEL_THRESHOLD;
    String outboundTracestate = continueTraceAndReinject(inboundTracestate);

    assertTrue(outboundTracestate.contains("ot=th:" + OTEL_THRESHOLD));
    assertTrue(!outboundTracestate.contains("rv:" + OTEL_MALFORMED_RANDOM_VALUE));
  }

  @ParameterizedTest
  @CsvSource({"0, 01, ef284ace7a91e1, 00", "2, 00, 00000000000000, 01"})
  void alignsOtelStateWithSampledFlagAfterCompoundExtraction(
      int datadogSamplingPriority,
      String inboundTraceFlags,
      String otelRandomValue,
      String outboundTraceFlags) {
    String inboundTracestate = DD_MEMBER + ",ot=rv:" + otelRandomValue + ";th:" + OTEL_THRESHOLD;
    String traceParent = TRACE_PARENT.substring(0, TRACE_PARENT.length() - 2) + inboundTraceFlags;
    Map<String, String> inboundHeaders =
        headers(
            DatadogHttpCodec.TRACE_ID_KEY,
            DATADOG_TRACE_ID,
            DatadogHttpCodec.SPAN_ID_KEY,
            DATADOG_PARENT_ID,
            DatadogHttpCodec.SAMPLING_PRIORITY_KEY,
            String.valueOf(datadogSamplingPriority),
            TRACE_PARENT_KEY,
            traceParent,
            TRACE_STATE_KEY,
            inboundTracestate);
    Config config = mock(Config.class);
    when(config.getTracePropagationStylesToExtract())
        .thenReturn(new LinkedHashSet<>(asList(DATADOG, TRACECONTEXT)));
    when(config.getxDatadogTagsMaxLength()).thenReturn(DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH);

    CoreTracer tracer = tracerBuilder().build();
    try {
      HttpCodec.Extractor extractor = HttpCodec.createExtractor(config, tracer::captureTraceConfig);
      ExtractedContext extractedContext =
          assertInstanceOf(
              ExtractedContext.class, extractor.extract(inboundHeaders, stringValuesMap()));
      assertEquals(inboundTracestate, extractedContext.getPropagationTags().getW3CTracestate());

      AgentSpan continuedSpan =
          tracer.buildSpan("test", "continued").asChildOf(extractedContext).start();
      Map<String, String> outboundHeaders = new HashMap<>();
      injector.inject((DDSpanContext) continuedSpan.spanContext(), outboundHeaders, Map::put);
      continuedSpan.finish();

      assertTrue(outboundHeaders.get(TRACE_PARENT_KEY).endsWith("-" + outboundTraceFlags));
      String outboundTracestate = outboundHeaders.get(TRACE_STATE_KEY);
      assertTrue(outboundTracestate.startsWith("dd=s:" + datadogSamplingPriority));
      assertTrue(outboundTracestate.contains("ot=rv:" + otelRandomValue));
      assertFalse(outboundTracestate.contains("th:" + OTEL_THRESHOLD));
    } finally {
      tracer.close();
    }
  }

  private String continueTraceAndReinject(String inboundTracestate) {
    Map<String, String> inboundHeaders =
        headers(TRACE_PARENT_KEY, TRACE_PARENT, TRACE_STATE_KEY, inboundTracestate);

    CoreTracer tracer = tracerBuilder().build();
    try {
      HttpCodec.Extractor extractor =
          W3CHttpCodec.newExtractor(Config.get(), tracer::captureTraceConfig);
      Object extracted = extractor.extract(inboundHeaders, stringValuesMap());
      assertInstanceOf(ExtractedContext.class, extracted);
      ExtractedContext extractedContext = (ExtractedContext) extracted;

      AgentSpan continuedSpan =
          tracer.buildSpan("test", "continued").asChildOf(extractedContext).start();
      assertEquals(extractedContext.getSamplingPriority(), continuedSpan.getSamplingPriority());

      Map<String, String> outboundHeaders = new HashMap<>();
      this.injector.inject((DDSpanContext) continuedSpan.spanContext(), outboundHeaders, Map::put);

      continuedSpan.finish();
      return outboundHeaders.get(TRACE_STATE_KEY);
    } finally {
      tracer.close();
    }
  }
}
