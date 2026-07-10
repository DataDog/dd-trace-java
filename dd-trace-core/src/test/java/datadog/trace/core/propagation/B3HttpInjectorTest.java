package datadog.trace.core.propagation;

import static datadog.trace.api.ConfigDefaults.DEFAULT_PROPAGATION_B3_PADDING_ENABLED;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY;
import static datadog.trace.core.propagation.B3TestHelper.spanIdOrPadded;
import static datadog.trace.core.propagation.B3TestHelper.traceIdOrPadded;
import static datadog.trace.core.propagation.B3TestHelper.trimHex;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.converter.PrioritySamplingConverter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class B3HttpInjectorTest extends AbstractHttpInjectorTest {

  private static final CarrierSetter<Map<String, String>> MAP_SETTER = Map::put;

  protected boolean tracePropagationB3Padding() {
    return DEFAULT_PROPAGATION_B3_PADDING_ENABLED;
  }

  @Override
  protected HttpCodec.Injector newInjector() {
    return B3HttpCodec.newCombinedInjector(tracePropagationB3Padding());
  }

  @TableTest({
    "scenario          | traceId | spanId | samplingPriority | expectedSamplingPriority",
    "unset             | 1       | 2      | UNSET            |                         ",
    "sampler keep      | 2       | 3      | SAMPLER_KEEP     | SAMPLER_KEEP            ",
    "sampler drop      | 4       | 5      | SAMPLER_DROP     | SAMPLER_DROP            ",
    "user keep         | 5       | 6      | USER_KEEP        | SAMPLER_KEEP            ",
    "user drop         | 6       | 7      | USER_DROP        | SAMPLER_DROP            ",
    "uint64 max unset  | -1      | -2     | UNSET            |                         ",
    "uint64 max-1 keep | -2      | -1     | SAMPLER_KEEP     | SAMPLER_KEEP            "
  })
  void injectHttpHeaders(
      long traceId,
      long spanId,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      @ConvertWith(PrioritySamplingConverter.class) Byte expectedSamplingPriority) {

    DDSpanContext spanContext =
        mockedSpanContext(DDTraceId.from(traceId), spanId, samplingPriority);

    Map<String, String> carrier = new HashMap<>();
    this.injector.inject(spanContext, carrier, MAP_SETTER);

    String traceIdHex = traceIdOrPadded(traceId, tracePropagationB3Padding());
    String spanIdHex = spanIdOrPadded(spanId, tracePropagationB3Padding());
    assertEquals(traceIdHex, carrier.get(TRACE_ID_KEY));
    assertEquals(spanIdHex, carrier.get(SPAN_ID_KEY));

    if (expectedSamplingPriority != null) {
      assertEquals(4, carrier.size());
      assertEquals(expectedSamplingPriority.toString(), carrier.get(SAMPLING_PRIORITY_KEY));
      assertEquals(
          traceIdHex + "-" + spanIdHex + "-" + expectedSamplingPriority, carrier.get(B3_KEY));
    } else {
      assertEquals(3, carrier.size());
      assertEquals(traceIdHex + "-" + spanIdHex, carrier.get(B3_KEY));
    }
  }

  @TableTest({
    "scenario             | traceId                            | spanId               ",
    "padded 64-bit        | '00001'                            | '00001'              ",
    "64-bit               | '463ac35c9f6413ad'                 | '463ac35c9f6413ad'   ",
    "128-bit              | '463ac35c9f6413ad48485a3953bb6124' | '1'                  ",
    "uint64 max traceId   | 'ffffffffffffffff'                 | '1'                  ",
    "128-bit high+low max | 'aaaaaaaaaaaaaaaaffffffffffffffff' | '1'                  ",
    "uint64 max spanId    | '1'                                | 'ffffffffffffffff'   ",
    "padded uint64 max    | '1'                                | '000ffffffffffffffff'"
  })
  void injectHttpHeadersWithExtractedOriginal(String traceId, String spanId) {
    Map<String, String> headers = new HashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create().setHeaderTags(emptyMap()).setBaggageMapping(emptyMap()).apply();
    HttpCodec.Extractor extractor =
        B3HttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);
    TagContext context = extractor.extract(headers, stringValuesMap());

    DDSpanContext mockedContext = mockedSpanContext(context);
    Map<String, String> carrier = new HashMap<>();
    this.injector.inject(mockedContext, carrier, MAP_SETTER);

    String traceIdHex = traceIdOrPadded(traceId, tracePropagationB3Padding());
    String spanIdHex = spanIdOrPadded(trimHex(spanId), tracePropagationB3Padding());
    assertEquals(traceIdHex, carrier.get(TRACE_ID_KEY));
    assertEquals(spanIdHex, carrier.get(SPAN_ID_KEY));
    assertEquals(traceIdHex + "-" + spanIdHex, carrier.get(B3_KEY));
    assertEquals(3, carrier.size());
  }

  private DDSpanContext mockedSpanContext(TagContext context) {
    return mockedSpanContext(context.getTraceId(), context.getSpanId(), UNSET);
  }

  private DDSpanContext mockedSpanContext(DDTraceId traceId, long spanId, int samplingPriority) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    return mockSpanContext(
        traceId,
        spanId,
        samplingPriority,
        "fakeOrigin",
        baggage,
        PropagationTags.factory().empty());
  }

  static class B3HttpInjectorNonPaddedTest extends B3HttpInjectorTest {
    @Override
    protected boolean tracePropagationB3Padding() {
      return false;
    }
  }
}
