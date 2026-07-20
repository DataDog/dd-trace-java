package datadog.trace.core.propagation;

import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static datadog.trace.core.propagation.W3CHttpCodec.OT_BAGGAGE_PREFIX;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_PARENT_KEY;
import static datadog.trace.core.propagation.W3CHttpCodec.TRACE_STATE_KEY;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.Config;
import datadog.trace.api.DD64bTraceId;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TraceConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.converter.SamplingMechanismConverter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class W3CHttpExtractorTest extends AbstractHttpExtractorTest {
  private static final long TEST_SPAN_ID = 1311768467463790320L;
  private static final DDTraceId TRACE_ID_ONE =
      DDTraceId.fromHex("00000000000000000000000000000001");
  private static final DDTraceId TRACE_ID_NO_HIGH_LOW_MAX =
      DDTraceId.fromHex("0000000000000000ffffffffffffffff");
  private static final DDTraceId TRACE_ID_LOW_MAX =
      DDTraceId.fromHex("123456789abcdef0ffffffffffffffff");

  @Override
  protected HttpCodec.Extractor newExtractor(
      Config config, Supplier<TraceConfig> traceConfigSupplier) {
    return W3CHttpCodec.newExtractor(config, traceConfigSupplier);
  }

  @TableTest({
    "scenario                              | traceparent                                                 | tpValid | traceId                  | spanId       | priority    ",
    "null traceparent                      |                                                             | false   |                          | 0            | UNSET       ",
    "all zeros trace id                    | '00-00000000000000000000000000000000-123456789abcdef0-01'   | false   |                          | 0            | UNSET       ",
    "too long trace id                     | '00-123456789abcdef00000000000000000-123456789abcdef0-01'   | false   |                          | 0            | UNSET       ",
    "all zeros span id                     | '00-00000000000000000000000000000001-0000000000000000-01'   | false   |                          | 0            | UNSET       ",
    "valid keep                            | '00-00000000000000000000000000000001-123456789abcdef0-01'   | true    | TRACE_ID_ONE             | SPAN_ID_TEST | SAMPLER_KEEP",
    "leading tab                           | '\t00-00000000000000000000000000000001-123456789abcdef0-01' | true    | TRACE_ID_ONE             | SPAN_ID_TEST | SAMPLER_KEEP",
    "trailing tab                          | '00-00000000000000000000000000000001-123456789abcdef0-01\t' | true    | TRACE_ID_ONE             | SPAN_ID_TEST | SAMPLER_KEEP",
    "surrounding spaces                    | ' 00-00000000000000000000000000000001-123456789abcdef0-01 ' | true    | TRACE_ID_ONE             | SPAN_ID_TEST | SAMPLER_KEEP",
    "max span id keep                      | '00-0000000000000000ffffffffffffffff-ffffffffffffffff-01'   | true    | TRACE_ID_NO_HIGH_LOW_MAX | SPAN_ID_MAX  | SAMPLER_KEEP",
    "max span id drop                      | '00-0000000000000000ffffffffffffffff-ffffffffffffffff-00'   | true    | TRACE_ID_NO_HIGH_LOW_MAX | SPAN_ID_MAX  | SAMPLER_DROP",
    "low max trace id drop                 | '00-123456789abcdef0ffffffffffffffff-123456789abcdef0-00'   | true    | TRACE_ID_LOW_MAX         | SPAN_ID_TEST | SAMPLER_DROP",
    "uppercase F in trace id low part      | '00-123456789abcdef0ffffffffffffffFf-123456789abcdef0-00'   | false   |                          | 0            | UNSET       ",
    "uppercase F in trace id high part     | '00-123456789abcdeF0ffffffffffffffff-123456789abcdef0-00'   | false   |                          | 0            | UNSET       ",
    "uppercase F in trace id mid           | '00-123456789abcdef0fffffffffFffffff-123456789abcdef0-00'   | false   |                          | 0            | UNSET       ",
    "uppercase A in span id                | '00-123456789abcdef0ffffffffffffffff-123456789Abcdef0-00'   | false   |                          | 0            | UNSET       ",
    "unicode a-umlaut in trace id start    | '00-123456789äbcdef0ffffffffffffffff-123456789abcdef0-00'   | false   |                          | 0            | UNSET       ",
    "unicode a-umlaut in trace id mid      | '00-123456789abcdef0ffffffffäfffffff-123456789abcdef0-00'   | false   |                          | 0            | UNSET       ",
    "unicode a-umlaut in span id           | '00-123456789abcdef0ffffffffffffffff-123456789äbcdef0-00'   | false   |                          | 0            | UNSET       ",
    "version 01 flags 02                   | '01-00000000000000000000000000000001-0000000000000001-02'   | true    | TRACE_ID_ONE             | SPAN_ID_ONE  | SAMPLER_DROP",
    "too long version                      | '000-0000000000000000000000000000001-0000000000000001-01'   | false   |                          | 0            | UNSET       ",
    "space inside trace id                 | '00-0000000000000000000000000000001 -0000000000000001-01'   | false   |                          | 0            | UNSET       ",
    "trace id too short                    | '00-0000000000000000000000000000001-0000000000000001-01'    | false   |                          | 0            | UNSET       ",
    "span id too short                     | '00-00000000000000000000000000000001-000000000000001-01'    | false   |                          | 0            | UNSET       ",
    "flags too short                       | '00-00000000000000000000000000000001-0000000000000001-0'    | false   |                          | 0            | UNSET       ",
    "version ff invalid                    | 'ff-00000000000000000000000000000001-0000000000000001-00'   | false   |                          | 0            | UNSET       ",
    "version fe flags 02                   | 'fe-00000000000000000000000000000001-0000000000000001-02'   | true    | TRACE_ID_ONE             | SPAN_ID_ONE  | SAMPLER_DROP",
    "extra data with version 00            | '00-00000000000000000000000000000001-0000000000000001-03-0' | false   |                          | 0            | UNSET       ",
    "invalid separator after flags with fe | 'fe-00000000000000000000000000000001-0000000000000001-02.0' | false   |                          | 0            | UNSET       "
  })
  void extractTraceparent(
      String traceparent,
      boolean tpValid,
      @ConvertWith(TraceIdTestConverter.class) DDTraceId traceId,
      @ConvertWith(SpanIdTestConverter.class) long spanId,
      @ConvertWith(PrioritySamplingConverter.class) byte priority) {
    Map<String, String> headers = headers(TRACE_PARENT_KEY, traceparent);

    TagContext result = this.extractor.extract(headers, stringValuesMap());

    if (tpValid) {
      assertInstanceOf(ExtractedContext.class, result);
      ExtractedContext context = (ExtractedContext) result;
      assertEquals(traceId, context.getTraceId());
      assertEquals(spanId, context.getSpanId());
      assertEquals(priority, context.getSamplingPriority());
    } else {
      assertNull(result);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"TRACE_ID_LOW_MAX", "TRACE_ID_NO_HIGH_LOW_MAX"})
  void checkMaxFromW3CTraceIds(@ConvertWith(TraceIdTestConverter.class) DDTraceId traceId) {
    assertEquals(DD64bTraceId.MAX.toLong(), traceId.toLong());
  }

  @TableTest({
    "scenario                                      | traceparent                                               | tracestate               | priority     | decisionMaker             | origin",
    "keep empty state                              | '00-00000000000000000000000000000001-123456789abcdef0-01' | ''                       | SAMPLER_KEEP | SamplingMechanism.DEFAULT |       ",
    "drop empty state                              | '00-00000000000000000000000000000001-123456789abcdef0-00' | ''                       | SAMPLER_DROP |                           |       ",
    "keep with user keep state                     | '00-00000000000000000000000000000001-123456789abcdef0-01' | 'dd=s:2;o:some'          | USER_KEEP    |                           | some  ",
    "keep with user keep state and manual dm       | '00-00000000000000000000000000000001-123456789abcdef0-01' | 'dd=s:2;o:some;t.dm:-4'  | USER_KEEP    | SamplingMechanism.MANUAL  | some  ",
    "drop with user keep state and manual dm       | '00-00000000000000000000000000000001-123456789abcdef0-00' | 'dd=s:2;o:some;t.dm:-4'  | SAMPLER_DROP |                           | some  ",
    "drop with user drop state                     | '00-00000000000000000000000000000001-123456789abcdef0-00' | 'dd=s:-1;o:some'         | USER_DROP    |                           | some  ",
    "drop with user drop state and manual dm       | '00-00000000000000000000000000000001-123456789abcdef0-00' | 'dd=s:-1;o:some;t.dm:-4' | USER_DROP    | SamplingMechanism.MANUAL  | some  ",
    "keep overrides user drop state with manual dm | '00-00000000000000000000000000000001-123456789abcdef0-01' | 'dd=s:-1;o:some;t.dm:-4' | SAMPLER_KEEP | SamplingMechanism.DEFAULT | some  "
  })
  void extractTraceparentTracestateAndHttpHeaders(
      String traceparent,
      String tracestate,
      @ConvertWith(PrioritySamplingConverter.class) byte priority,
      @ConvertWith(SamplingMechanismConverter.class) Byte decisionMaker,
      String origin) {
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_PARENT_KEY, traceparent,
        TRACE_STATE_KEY, tracestate,
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info",
        SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info",
        SOME_CUSTOM_BAGGAGE_HEADER_2, "my-interesting-baggage-info-2"
    );
    // spotless:on

    ExtractedContext context =
        (ExtractedContext) this.extractor.extract(headers, stringValuesMap());

    assertEquals(TRACE_ID_ONE, context.getTraceId());
    assertEquals(TEST_SPAN_ID, context.getSpanId());
    Map<String, String> expectedBaggage = new HashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put("some-baggage", "my-interesting-baggage-info");
    expectedBaggage.put("some-CaseSensitive-baggage", "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap("some-tag", "my-interesting-info"), context.getTags());
    assertEquals(priority, context.getSamplingPriority());
    Map<String, String> expectedPTags =
        decisionMaker != null ? singletonMap("_dd.p.dm", "-" + decisionMaker) : emptyMap();
    assertEquals(expectedPTags, context.getPropagationTags().createTagMap());
    if (origin != null) {
      assertEquals(origin, context.getOrigin().toString());
    }
  }

  @Test
  void extractHeaderTagsWithNoPropagation() {
    Map<String, String> headers = headers(SOME_HEADER, "my-interesting-info");

    TagContext context = this.extractor.extract(headers, stringValuesMap());

    assertFalse(context instanceof ExtractedContext);
    assertEquals(singletonMap("some-tag", "my-interesting-info"), context.getTags());
  }

  @TableTest({
    "scenario            | endToEndStartTime",
    "zero start time     | 0                ",
    "non-zero start time | 1610001234       "
  })
  void extractHttpHeadersWithEndToEnd(long endToEndStartTime) {
    // spotless:off
    Map<String, String> headers = headers(
        "", "empty key",
        TRACE_PARENT_KEY, "00-00000000000000000000000000000001-123456789abcdef0-01",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "t0", String.valueOf(endToEndStartTime),
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_HEADER, "my-interesting-info",
        SOME_CUSTOM_BAGGAGE_HEADER, "my-interesting-baggage-info",
        SOME_CUSTOM_BAGGAGE_HEADER_2, "my-interesting-baggage-info-2"
    );
    // spotless:on

    ExtractedContext context = (ExtractedContext) extractor.extract(headers, stringValuesMap());

    assertEquals(TRACE_ID_ONE, context.getTraceId());
    assertEquals(TEST_SPAN_ID, context.getSpanId());
    Map<String, String> expectedBaggage = new LinkedHashMap<>();
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    expectedBaggage.put("some-baggage", "my-interesting-baggage-info");
    expectedBaggage.put("some-CaseSensitive-baggage", "my-interesting-baggage-info-2");
    assertEquals(expectedBaggage, context.getBaggage());
    assertEquals(singletonMap("some-tag", "my-interesting-info"), context.getTags());
    assertEquals(endToEndStartTime * 1_000_000L, context.getEndToEndStartTime());
  }

  @TableTest({
    "scenario                   | tpValid | traceparent                                              ",
    "invalid all-zeros trace id | false   | '00-00000000000000000000000000000000-123456789abcdef0-01'",
    "invalid all-zeros span id  | false   | '00-00000000000000000000000000000001-0000000000000000-01'",
    "valid traceparent          | true    | '00-00000000000000000000000000000001-0000000000000001-01'"
  })
  void baggageIsMappedOnContextCreation(boolean tpValid, String traceparent) {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_PARENT_KEY, traceparent,
        SOME_CUSTOM_BAGGAGE_HEADER, "mappedBaggageValue",
        OT_BAGGAGE_PREFIX + "k1", "v1",
        OT_BAGGAGE_PREFIX + "k2", "v2",
        SOME_ARBITRARY_HEADER, "my-interesting-info"
    );
    // spotless:on

    TagContext context = extractor.extract(headers, stringValuesMap());

    assertNotNull(context);
    if (tpValid) {
      assertEquals(TRACE_ID_ONE, context.getTraceId());
      assertEquals(1L, context.getSpanId());
    }
    Map<String, String> expectedBaggage = new LinkedHashMap<>();
    expectedBaggage.put("some-baggage", "mappedBaggageValue");
    expectedBaggage.put("k1", "v1");
    expectedBaggage.put("k2", "v2");
    assertEquals(expectedBaggage, context.getBaggage());
  }

  @TableTest({
    "scenario         | traceparent                                               | tracestate                  | consistent",
    "empty state      | '00-123456789abcdef00fedcba987654321-123456789abcdef0-01' | ''                          | true      ",
    "consistent tid   | '00-123456789abcdef00fedcba987654321-123456789abcdef0-01' | 'dd=t.tid:123456789abcdef0' | true      ",
    "inconsistent tid | '00-123456789abcdef00fedcba987654321-123456789abcdef0-01' | 'dd=t.tid:123456789abcdef1' | false     "
  })
  void markInconsistentTidAsPropagationError(
      String traceparent, String tracestate, boolean consistent) {
    // spotless:off
    Map<String, String> headers = headers(
        TRACE_PARENT_KEY, traceparent,
        TRACE_STATE_KEY, tracestate);
    // spotless:on

    ExtractedContext context = (ExtractedContext) extractor.extract(headers, stringValuesMap());

    String tid = tracestate.isEmpty() ? "" : tracestate.substring(9);
    Map<String, String> defaultTags = new LinkedHashMap<>();
    defaultTags.put("_dd.p.dm", "-0");
    defaultTags.put("_dd.p.tid", "123456789abcdef0");
    Map<String, String> expectedTags = new LinkedHashMap<>(defaultTags);
    if (!consistent) {
      expectedTags.put("_dd.propagation_error", "inconsistent_tid " + tid);
    }
    assertEquals(expectedTags, context.getPropagationTags().createTagMap());
  }

  static final class TraceIdTestConverter implements ArgumentConverter {
    @Override
    public Object convert(Object source, ParameterContext context)
        throws ArgumentConversionException {
      if (source == null) {
        return null;
      }
      switch (source.toString()) {
        case "TRACE_ID_ONE":
          return TRACE_ID_ONE;
        case "TRACE_ID_NO_HIGH_LOW_MAX":
          return TRACE_ID_NO_HIGH_LOW_MAX;
        case "TRACE_ID_LOW_MAX":
          return TRACE_ID_LOW_MAX;
        default:
          return source;
      }
    }
  }

  static final class SpanIdTestConverter implements ArgumentConverter {
    @Override
    public Object convert(Object source, ParameterContext context)
        throws ArgumentConversionException {
      if (source == null) {
        return null;
      }
      String s = source.toString();
      switch (s) {
        case "SPAN_ID_MAX":
          return DDSpanId.MAX;
        case "SPAN_ID_TEST":
          return TEST_SPAN_ID;
        case "SPAN_ID_ONE":
          return 1;
        default:
          return Long.parseLong(s);
      }
    }
  }
}
