package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.HaystackHttpCodec.HAYSTACK_SPAN_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.HAYSTACK_TRACE_ID_BAGGAGE_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.HaystackHttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.HaystackHttpCodec.TRACE_ID_KEY;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static datadog.trace.test.junit.utils.converter.TraceIdConverter.TRACE_ID_MAX_PLUS_1;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.test.junit.utils.converter.TraceIdConverter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class HaystackHttpExtractorTest extends AbstractHttpExtractorTest {
  @Override
  protected HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return HaystackHttpCodec.newExtractor(config, traceConfigSupplier);
  }

  @TableTest({
    "scenario         | traceId | spanId                 | traceUuid                              | spanUuid                              ",
    "small ids        | '1'     | '2'                    | '44617461-646f-6721-0000-000000000001' | '44617461-646f-6721-0000-000000000002'",
    "incrementing ids | '2'     | '3'                    | '44617461-646f-6721-0000-000000000002' | '44617461-646f-6721-0000-000000000003'",
    "uint64 max       | 'MAX'   | '18446744073709551609' | '44617461-646f-6721-ffff-ffffffffffff' | '44617461-646f-6721-ffff-fffffffffff9'",
    "uint64 max-1     | 'MAX-1' | '18446744073709551608' | '44617461-646f-6721-ffff-fffffffffffe' | '44617461-646f-6721-ffff-fffffffffff8'"
  })
  void extractHttpHeaders(
      @ConvertWith(TraceIdConverter.class) String traceId,
      String spanId,
      String traceUuid,
      String spanUuid) {
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_ID_KEY, traceUuid,
        SPAN_ID_KEY, spanUuid,
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "%76%32", // v2 encoded once
        OT_BAGGAGE_PREFIX + "k3", "%25%37%36%25%33%33", // v3 encoded twice
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
    expectedBaggage.put("k3", "%76%33"); // expect value decoded only once
    expectedBaggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, traceUuid);
    expectedBaggage.put(HAYSTACK_SPAN_ID_BAGGAGE_KEY, spanUuid);
    expectedBaggage.put(SOME_BAGGAGE, "my-interesting-baggage-info");
    expectedBaggage.put(SOME_CASE_SENSITIVE_BAGGAGE, "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap(SOME_TAG, "my-interesting-info"), context.getTags());
    assertEquals(SAMPLER_KEEP, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  @Test
  void extractHeaderTagsWithNoPropagation() {
    Map<String, String> headers = headers(SOME_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
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
    "scenario       | traceId                                | spanId                                 | ctxCreated",
    "negative trace | '-1'                                   | '1'                                    | false     ",
    "negative span  | '1'                                    | '-1'                                   | false     ",
    "zero traceId   | '0'                                    | '1'                                    | true      ",
    "uuid format    | '44617461-646f-6721-463a-c35c9f6413ad' | '44617461-646f-6721-463a-c35c9f6413ad' | true      "
  })
  void baggageIsMappedOnContextCreation(String traceId, String spanId, boolean ctxCreated) {
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
      expectedBaggage.put(HAYSTACK_TRACE_ID_BAGGAGE_KEY, traceId);
      expectedBaggage.put(HAYSTACK_SPAN_ID_BAGGAGE_KEY, spanId);
      expectedBaggage.put(SOME_BAGGAGE, "mappedBaggageValue");
      expectedBaggage.put("k1", "v1");
      expectedBaggage.put("k2", "v2");
      assertEquals(expectedBaggage, context.getBaggage());
    } else {
      assertNull(context);
    }
  }

  @TableTest({
    "scenario               | traceId                                | spanId                                 | expectedTraceIdLong | expectedSpanId      | ctxCreated",
    "negative traceId       | '-1'                                   | '1'                                    |                     | 0                   | false     ",
    "negative spanId        | '1'                                    | '-1'                                   |                     | 0                   | false     ",
    "zero traceId           | '0'                                    | '1'                                    |                     | 0                   | true      ",
    "padded ones            | '00001'                                | '00001'                                | 1                   | 1                   | true      ",
    "64-bit hex             | '463ac35c9f6413ad'                     | '463ac35c9f6413ad'                     | 5060571933882717101 | 5060571933882717101 | true      ",
    "128-bit hex truncated  | '463ac35c9f6413ad48485a3953bb6124'     | '1'                                    | 5208512171318403364 | 1                   | true      ",
    "uuid format same       | '44617461-646f-6721-463a-c35c9f6413ad' | '44617461-646f-6721-463a-c35c9f6413ad' | 5060571933882717101 | 5060571933882717101 | true      ",
    "uint64 max 64-bit      | 'ffffffffffffffff'                     | '1'                                    | -1                  | 1                   | true      ",
    "128-bit high+low max   | 'aaaaaaaaaaaaaaaaffffffffffffffff'     | '1'                                    | -1                  | 1                   | true      ",
    "traceId too long high1 | '1ffffffffffffffffffffffffffffffff'    | '1'                                    |                     | 1                   | false     ",
    "traceId too long high0 | '0ffffffffffffffffffffffffffffffff'    | '1'                                    |                     | 1                   | false     ",
    "uint64 max spanId      | '1'                                    | 'ffffffffffffffff'                     | 1                   | -1                  | true      ",
    "spanId too long        | '1'                                    | '1ffffffffffffffff'                    |                     | 0                   | false     ",
    "padded uint64 max span | '1'                                    | '000ffffffffffffffff'                  | 1                   | -1                  | true      "
  })
  void extract128BitIdTruncatesIdTo64Bit(
      String traceId,
      String spanId,
      Long expectedTraceIdLong,
      long expectedSpanId,
      boolean ctxCreated) {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, traceId,
        SPAN_ID_KEY, spanId);
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    if (expectedTraceIdLong != null) {
      assertEquals(DDTraceId.from(expectedTraceIdLong), context.getTraceId());
      assertEquals(expectedSpanId, context.getSpanId());
    }
    if (ctxCreated) {
      assertNotNull(context);
    } else {
      assertNull(context);
    }
  }
}
