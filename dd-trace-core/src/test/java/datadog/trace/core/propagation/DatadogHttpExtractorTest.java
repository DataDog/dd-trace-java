package datadog.trace.core.propagation;

import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS_COMMA_ALLOWED;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.DatadogHttpCodec.DATADOG_TAGS_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.ORIGIN_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.DatadogHttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.DatadogHttpCodec.TRACE_ID_KEY;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static datadog.trace.test.junit.utils.converter.TraceIdConverter.TRACE_ID_MAX_PLUS_1;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.Config;
import datadog.trace.api.DD128bTraceId;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.internal.util.LongStringUtils;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.converter.TraceIdConverter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class DatadogHttpExtractorTest extends AbstractHttpExtractorTest {
  @Override
  protected HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return DatadogHttpCodec.newExtractor(config, traceConfigSupplier);
  }

  @TableTest({
    "scenario          | traceId | spanId  | samplingPriority | origin  ",
    "unset no origin   | '1'     | '2'     | UNSET            |         ",
    "keep with origin  | '2'     | '3'     | SAMPLER_KEEP     | 'saipan'",
    "uint64 max unset  | 'MAX'   | 'MAX-1' | UNSET            | 'saipan'",
    "uint64 max-1 keep | 'MAX-1' | 'MAX'   | SAMPLER_KEEP     | 'saipan'"
  })
  void extractHttpHeaders(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      String origin) {
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_ID_KEY, traceId,
        SPAN_ID_KEY, spanId,
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info,and-more",
        SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info",
        SOME_CUSTOM_BAGGAGE_HEADER_2, "my-interesting-baggage-info-2",
        SAMPLING_PRIORITY_KEY, samplingPriority != UNSET ? String.valueOf(samplingPriority) : null,
        ORIGIN_KEY, origin
    );
    // spotless:on

    ExtractedContext context = (ExtractedContext) extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.from(traceId), context.getTraceId());
    assertEquals(DDSpanId.from(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info,and-more"), context.getTags());
    assertEquals(samplingPriority, context.getSamplingPriority());
    assertEquals(origin, asString(context.getOrigin()));
  }

  @WithConfig(key = REQUEST_HEADER_TAGS_COMMA_ALLOWED, value = "false")
  @Test
  void extractHttpHeadersWithoutComma() {
    // Recreate extractor with the new comma config
    this.extractor.cleanup();
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create().setHeaderTags(singletonMap(SOME_HEADER, SOME_TAG)).apply();
    this.extractor = DatadogHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);

    String headerWithComma = "my-interesting-info,and-more";
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, "1",
        SPAN_ID_KEY, "2",
        SOME_HEADER, headerWithComma
    );
    // spotless:on

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    String expectedHeader = "my-interesting-info";
    assertEquals(expectedHeader, context.getTags().getString(SOME_TAG));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void extractHeaderTagsWithNoPropagation(boolean withOrigin) {
    // spotless:off
    Map<String, String> headers = headers(
        ORIGIN_KEY, withOrigin ? "my-origin" : null,
        SOME_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
    if (withOrigin) {
      assertEquals("my-origin", asString(context.getOrigin()));
    }
  }

  @TableTest({
    "scenario            | hexId                             ",
    "64-bit short        | '1'                               ",
    "64-bit max chars    | '123456789abcdef0'                ",
    "128-bit             | '123456789abcdef0123456789abcdef0'",
    "128-bit zero middle | '64184f2400000000123456789abcdef0'",
    "128-bit all f       | 'ffffffffffffffffffffffffffffffff'"
  })
  void extractHttpHeadersWith128BitTraceId(String hexId) {
    DD128bTraceId traceId = DD128bTraceId.fromHex(hexId);
    boolean is128bTrace = traceId.toHighOrderLong() != 0;

    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, traceId.toString(),
        SPAN_ID_KEY, "2",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info",
        DATADOG_TAGS_KEY, is128bTrace
            ? "_dd.p.tid=" + LongStringUtils.toHexStringPadded(traceId.toHighOrderLong(), 16)
            : null
    );
    // spotless:on

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    DDTraceId expectedTraceId = is128bTrace ? traceId : DD64bTraceId.from(traceId.toLong());
    assertEquals(expectedTraceId, context.getTraceId());
    assertEquals(DDSpanId.from("2"), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
  }

  @Test
  void extractHttpHeadersWithInvalidNonNumericId() {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, "traceId",
        SPAN_ID_KEY, "spanId",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNull(context);
  }

  @Test
  void extractHttpHeadersWithOutOfRangeTraceId() {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, TRACE_ID_MAX_PLUS_1,
        SPAN_ID_KEY, "0",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNull(context);
  }

  @Test
  void extractHttpHeadersWithOutOfRangeSpanId() {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, "0",
        SPAN_ID_KEY, "-1",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertNull(context);
  }

  @TableTest({
    "scenario             | traceId | spanId  | expectExtraction",
    "negative traceId     | '-1'    | '1'     | false           ",
    "negative spanId      | '1'     | '-1'    | false           ",
    "zero traceId         | '0'     | '1'     | false           ",
    "zero spanId          | '1'     | '0'     | true            ",
    "uint64 max traceId   | 'MAX'   | '1'     | true            ",
    "out-of-range traceId | 'MAX+1' | '1'     | false           ",
    "uint64 max spanId    | '1'     | 'MAX'   | true            ",
    "out-of-range spanId  | '1'     | 'MAX+1' | false           "
  })
  void moreIdRangeValidation(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId,
      boolean expectExtraction) {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, traceId,
        SPAN_ID_KEY, spanId
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    if (expectExtraction) {
      ExtractedContext extracted = assertInstanceOf(ExtractedContext.class, context);
      assertEquals(DDTraceId.from(traceId), extracted.getTraceId());
      assertEquals(DDSpanId.from(spanId), extracted.getSpanId());
    } else {
      assertNull(context);
    }
  }

  @TableTest({
    "scenario   | traceId | spanId | endToEndStartTime",
    "zero       | '1'     | '2'    | 0                ",
    "epoch 2021 | '2'     | '3'    | 1610001234       "
  })
  void extractHttpHeadersWithEndToEnd(String traceId, String spanId, long endToEndStartTime) {
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_ID_KEY, traceId,
        SPAN_ID_KEY, spanId,
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "t0", String.valueOf(endToEndStartTime),
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info",
        SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info",
        SOME_CUSTOM_BAGGAGE_HEADER_2, "my-interesting-baggage-info-2"
    );
    // spotless:on

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    assertEquals(DDTraceId.from(traceId), context.getTraceId());
    assertEquals(DDSpanId.from(spanId), context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
    assertEquals(endToEndStartTime * 1000000L, context.getEndToEndStartTime());
  }

  @TableTest({
    "scenario         | traceId | spanId  | ctxCreated",
    "negative traceId | '-1'    | '1'     | false     ",
    "negative spanId  | '1'     | '-1'    | false     ",
    "zero traceId     | '0'     | '1'     | true      ",
    "uint64 max-1 ids | 'MAX-1' | 'MAX-1' | true      "
  })
  void baggageIsMappedOnContextCreation(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId,
      boolean ctxCreated) {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, traceId,
        SPAN_ID_KEY, spanId,
        SOME_CUSTOM_BAGGAGE_HEADER, "mappedBaggageValue",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_ARBITRARY_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    if (ctxCreated) {
      assertNotNull(context);
      Map<String, String> expectedBaggage = new HashMap<>();
      expectedBaggage.put(SOME_BAGGAGE, "mappedBaggageValue");
      expectedBaggage.put("k1", "v1");
      expectedBaggage.put("k2", "v2");
      assertEquals(expectedBaggage, context.getBaggage());
    } else {
      assertNull(context);
    }
  }

  private static String asString(CharSequence cs) {
    return cs == null ? null : cs.toString();
  }
}
