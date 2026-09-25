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

/** Exercises {@code ot=} through real W3C extraction, continuation, and reinjection. */
class W3COtelTraceStateContinuationTest extends DDCoreJavaSpecification {
  private static final String TRACE_PARENT =
      "00-00000000000000000000000000000001-123456789abcdef0-01";
  private static final String RANDOM_VALUE = "ef284ace7a91e1";
  private static final String THRESHOLD = "e6666666666668";
  private static final String DD_MEMBER = "dd=s:2;p:123456789abcdef0";

  private final HttpCodec.Injector injector = W3CHttpCodec.newInjector(emptyMap());

  @Test
  void roundTripsValidOtelState() {
    String inbound = DD_MEMBER + ",ot=rv:" + RANDOM_VALUE + ";th:" + THRESHOLD;

    assertTrue(continueTraceAndReinject(inbound).contains("ot=rv:" + RANDOM_VALUE + ";th:"));
  }

  @Test
  void malformedRandomValueRemovesManagedPair() {
    String inbound = DD_MEMBER + ",ot=rv:zz;th:" + THRESHOLD;
    String outbound = continueTraceAndReinject(inbound);

    assertFalse(outbound.contains("ot="));
    assertFalse(outbound.contains("th:" + THRESHOLD));
  }

  @ParameterizedTest
  @CsvSource({
    "0, 01, ef284ace7a91e1, 00, false",
    "2, 00, 00000000000000, 01, false",
    "0, 00, 00000000000000, 00, true",
    "2, 01, ef284ace7a91e1, 01, true"
  })
  void compoundExtractionKeepsFirstPriorityAndReconcilesOtelState(
      int datadogPriority,
      String inboundFlags,
      String randomValue,
      String outboundFlags,
      boolean thresholdExpected) {
    String traceParent = TRACE_PARENT.substring(0, TRACE_PARENT.length() - 2) + inboundFlags;
    String inboundTracestate =
        DD_MEMBER + ",ot=rv:" + randomValue + ";th:" + THRESHOLD + ",vendor=state";
    Map<String, String> inboundHeaders =
        headers(
            DatadogHttpCodec.TRACE_ID_KEY,
            "1",
            DatadogHttpCodec.SPAN_ID_KEY,
            "2",
            DatadogHttpCodec.SAMPLING_PRIORITY_KEY,
            String.valueOf(datadogPriority),
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
      ExtractedContext extracted =
          assertInstanceOf(
              ExtractedContext.class, extractor.extract(inboundHeaders, stringValuesMap()));
      assertEquals(inboundTracestate, extracted.getPropagationTags().getW3CTracestate());

      AgentSpan span = tracer.buildSpan("test", "continued").asChildOf(extracted).start();
      Map<String, String> outboundHeaders = new HashMap<>();
      injector.inject((DDSpanContext) span.spanContext(), outboundHeaders, Map::put);
      span.finish();

      assertTrue(outboundHeaders.get(TRACE_PARENT_KEY).endsWith("-" + outboundFlags));
      String outboundTracestate = outboundHeaders.get(TRACE_STATE_KEY);
      assertTrue(outboundTracestate.startsWith("dd=s:" + datadogPriority));
      assertTrue(outboundTracestate.contains("ot=rv:" + randomValue));
      assertTrue(outboundTracestate.endsWith("vendor=state"));
      assertEquals(thresholdExpected, outboundTracestate.contains("th:" + THRESHOLD));
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
      ExtractedContext extracted =
          assertInstanceOf(
              ExtractedContext.class, extractor.extract(inboundHeaders, stringValuesMap()));

      AgentSpan span = tracer.buildSpan("test", "continued").asChildOf(extracted).start();
      assertEquals(extracted.getSamplingPriority(), span.getSamplingPriority());

      Map<String, String> outboundHeaders = new HashMap<>();
      injector.inject((DDSpanContext) span.spanContext(), outboundHeaders, Map::put);
      span.finish();
      return outboundHeaders.get(TRACE_STATE_KEY);
    } finally {
      tracer.close();
    }
  }
}
