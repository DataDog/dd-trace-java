package datadog.trace.core.propagation;

import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.B3_SPAN_ID;
import static datadog.trace.core.propagation.B3HttpCodec.B3_TRACE_ID;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_ACCEPT;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.junit.utils.converter.PrioritySamplingConverter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class B3HttpExtractorTest extends AbstractHttpExtractorTest {
  private static final String SOME_VALUE = "my-interesting-info";

  @Override
  protected HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return B3HttpCodec.newExtractor(config, traceConfigSupplier);
  }

  @TableTest({
    "scenario          | traceIdHex         | spanIdHex          | samplingPriority | expectedSamplingPriority",
    "no priority       | '1'                | '2'                |                  | UNSET                   ",
    "sampler keep      | '2'                | '3'                | 1                | SAMPLER_KEEP            ",
    "sampler drop      | '3'                | '4'                | 0                | SAMPLER_DROP            ",
    "uint64 max drop   | 'ffffffffffffffff' | 'fffffffffffffffe' | 0                | SAMPLER_DROP            ",
    "uint64 max-1 keep | 'fffffffffffffffe' | 'ffffffffffffffff' | 1                | SAMPLER_KEEP            "
  })
  void extractHttpHeaders(
      String traceIdHex,
      String spanIdHex,
      Integer samplingPriority,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedSamplingPriority) {
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_ID_KEY, traceIdHex,
        SPAN_ID_KEY, spanIdHex,
        SOME_HEADER, SOME_VALUE,
        SAMPLING_PRIORITY_KEY, samplingPriority != null ? samplingPriority.toString() : null);
    // spotless:on

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    assertEquals(B3TraceId.fromHex(traceIdHex), context.getTraceId());
    assertEquals(DDSpanId.fromHex(spanIdHex), context.getSpanId());
    assertEquals(emptyMap(), context.getBaggage());
    assertEquals(expectedB3Tags(context), context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  @TableTest({
    "scenario              | b3      | expectedTraceIdHex | expectedSpanId | expectedSamplingPriority",
    "b3 takes precedence   | '2-3-0' | '2'                | 3              | SAMPLER_DROP            ",
    "b3 without priority   | '2-3'   | '2'                | 3              | UNSET                   ",
    "invalid b3 falls back | '0'     | '1'                | 2              | SAMPLER_KEEP            ",
    "absent b3 falls back  |         | '1'                | 2              | SAMPLER_KEEP            "
  })
  void extractHttpHeadersWithB3HeaderAtTheBeginning(
      String b3,
      String expectedTraceIdHex,
      long expectedSpanId,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedSamplingPriority) {
    String traceIdHex = "1";
    String spanIdHex = "2";
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        B3_KEY, b3,
        TRACE_ID_KEY, traceIdHex,
        SPAN_ID_KEY, spanIdHex,
        SOME_HEADER, SOME_VALUE,
        SAMPLING_PRIORITY_KEY, SAMPLING_PRIORITY_ACCEPT
    );
    // spotless:on

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    assertB3MultiOrSingleContext(
        context, expectedTraceIdHex, expectedSpanId, expectedSamplingPriority);
  }

  @TableTest({
    "scenario              | b3      | expectedTraceIdHex | expectedSpanId | expectedSamplingPriority",
    "b3 takes precedence   | '2-3-0' | '2'                | 3              | SAMPLER_DROP            ",
    "b3 without priority   | '2-3'   | '2'                | 3              | UNSET                   ",
    "invalid b3 falls back | '0'     | '1'                | 2              | SAMPLER_KEEP            ",
    "absent b3 falls back  |         | '1'                | 2              | SAMPLER_KEEP            "
  })
  void extractHttpHeadersWithB3HeaderAtTheEnd(
      String b3,
      String expectedTraceIdHex,
      long expectedSpanId,
      @ConvertWith(PrioritySamplingConverter.class) byte expectedSamplingPriority) {
    String traceIdHex = "1";
    String spanIdHex = "2";
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_ID_KEY, traceIdHex,
        SPAN_ID_KEY, spanIdHex,
        B3_KEY, b3,
        SOME_HEADER, SOME_VALUE,
        SAMPLING_PRIORITY_KEY, SAMPLING_PRIORITY_ACCEPT
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertB3MultiOrSingleContext(
        context, expectedTraceIdHex, expectedSpanId, expectedSamplingPriority);
  }

  private void assertB3MultiOrSingleContext(
      TagContext context,
      String expectedTraceIdHex,
      long expectedSpanId,
      int expectedSamplingPriority) {
    assertEquals(B3TraceId.fromHex(expectedTraceIdHex), context.getTraceId());
    assertEquals(expectedSpanId, context.getSpanId());
    assertFalse(context.baggageItems().iterator().hasNext());
    assertEquals(expectedB3Tags(context), context.getTags());
    assertEquals(expectedSamplingPriority, context.getSamplingPriority());
    assertNull(context.getOrigin());
  }

  private Map<String, Object> expectedB3Tags(TagContext context) {
    Map<String, Object> expected = new HashMap<>();
    expected.put(B3_TRACE_ID, ((B3TraceId) context.getTraceId()).getOriginal());
    expected.put(B3_SPAN_ID, DDSpanId.toHexString(context.getSpanId()));
    expected.put(SOME_TAG, SOME_VALUE);
    return expected;
  }

  @TableTest({
    "scenario               | traceId                             | spanId              | expectedTraceIdHex                 | expectedSpanId     ",
    "negative traceId       | '-1'                                | '1'                 |                                    |                    ",
    "negative spanId        | '1'                                 | '-1'                |                                    |                    ",
    "zero traceId           | '0'                                 | '1'                 |                                    |                    ",
    "padded traceId         | '00001'                             | '1'                 | '00001'                            | 1                  ",
    "64-bit ids             | '463ac35c9f6413ad'                  | '463ac35c9f6413ad'  | '463ac35c9f6413ad'                 | 5060571933882717101",
    "128-bit traceId        | '463ac35c9f6413ad48485a3953bb6124'  | '1'                 | '463ac35c9f6413ad48485a3953bb6124' | 1                  ",
    "uint64 max traceId     | 'ffffffffffffffff'                  | '1'                 | 'ffffffffffffffff'                 | 1                  ",
    "128-bit high-low max   | 'aaaaaaaaaaaaaaaaffffffffffffffff'  | '1'                 | 'aaaaaaaaaaaaaaaaffffffffffffffff' | 1                  ",
    "traceId too long high1 | '1ffffffffffffffffffffffffffffffff' | '1'                 |                                    |                    ",
    "traceId too long high0 | '0ffffffffffffffffffffffffffffffff' | '1'                 |                                    |                    ",
    "uint64 max spanId      | '1'                                 | 'ffffffffffffffff'  | '1'                                | -1                 ",
    "spanId too long        | '1'                                 | '1ffffffffffffffff' |                                    |                    "
  })
  void extract128BitIdTruncatesIdTo64Bit(
      String traceId, String spanId, String expectedTraceIdHex, Long expectedSpanId) {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, traceId,
        SPAN_ID_KEY, spanId
    );
    // spotless:on

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    if (expectedTraceIdHex != null) {
      assertInstanceOf(ExtractedContext.class, context);
      B3TraceId expectedTraceId = B3TraceId.fromHex(expectedTraceIdHex);
      assertEquals(expectedTraceId, context.getTraceId());
      long spanIdValue = expectedSpanId == null ? 0L : expectedSpanId;
      assertEquals(spanIdValue, context.getSpanId());
      assertEquals(expectedTraceId.getOriginal(), context.getTags().getString(B3_TRACE_ID));
      if (expectedSpanId == null) {
        assertNull(context.getTags().getString(B3_SPAN_ID));
      } else {
        assertEquals(DDSpanId.toHexString(expectedSpanId), context.getTags().getString(B3_SPAN_ID));
      }
    } else if (context != null) {
      assertInstanceOf(TagContext.class, context);
      assertFalse(context instanceof ExtractedContext);
    }
  }

  @Test
  void extractHeaderTagsWithNoPropagation() {
    TagContext context =
        this.extractor.extract(singletonMap(SOME_HEADER, SOME_VALUE), stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, SOME_VALUE), context.getTags());
  }

  @Test
  void extractHttpHeadersWithInvalidNonNumericId() {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, "traceId",
        SPAN_ID_KEY, "spanId",
        SOME_HEADER, SOME_VALUE
    );
    // spotless:on

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, SOME_VALUE), context.getTags());
  }

  @Test
  void extractHttpHeadersWithOutOfRangeSpanId() {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, "0",
        SPAN_ID_KEY, "-1",
        SOME_HEADER, SOME_VALUE
    );
    // spotless:on

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap(SOME_TAG, SOME_VALUE), context.getTags());
  }

  @TableTest({
    "scenario             | traceId                            | spanId                | expectedSpanId     ",
    "padded 64-bit        | '00001'                            | '00001'               | 1                  ",
    "normal 64-bit        | '463ac35c9f6413ad'                 | '463ac35c9f6413ad'    | 5060571933882717101",
    "128-bit truncated    | '463ac35c9f6413ad48485a3953bb6124' | '1'                   | 1                  ",
    "uint64 max traceId   | 'ffffffffffffffff'                 | '1'                   | 1                  ",
    "128-bit high+low max | 'aaaaaaaaaaaaaaaaffffffffffffffff' | '1'                   | 1                  ",
    "uint64 max spanId    | '1'                                | 'ffffffffffffffff'    | -1                 ",
    "padded uint64 max    | '1'                                | '000ffffffffffffffff' | -1                 "
  })
  void extractIdsWhileRetainingTheOriginalString(
      String traceId, String spanId, long expectedSpanId) {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_ID_KEY, traceId,
        SPAN_ID_KEY, spanId
    );
    // spotless:on
    B3TraceId expectedTraceId = B3TraceId.fromHex(traceId);

    ExtractedContext context = (ExtractedContext) extractor.extract(headers, stringValuesMap());

    assertEquals(expectedTraceId, context.getTraceId());
    assertEquals(traceId, ((B3TraceId) context.getTraceId()).getOriginal());
    assertEquals(expectedSpanId, context.getSpanId());
    assertEquals(trimmed(spanId), DDSpanId.toHexString(context.getSpanId()));
  }

  private static String trimmed(String hex) {
    int length = hex.length();
    int firstNonZero = 0;
    while (firstNonZero < length && hex.charAt(firstNonZero) == '0') {
      firstNonZero++;
    }
    if (firstNonZero == length) {
      return "0";
    }
    return hex.substring(firstNonZero, length);
  }
}
