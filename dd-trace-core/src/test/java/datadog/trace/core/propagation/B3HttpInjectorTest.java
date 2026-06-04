package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY;
import static java.util.Collections.emptyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class B3HttpInjectorTest extends DDCoreJavaSpecification {

  private static final CarrierSetter<Map<String, String>> MAP_SETTER = Map::put;

  protected boolean tracePropagationB3Padding() {
    return false;
  }

  private String idOrPadded(long id, int size) {
    return idOrPadded(Long.toHexString(id), size);
  }

  private String idOrPadded(String id, int size) {
    if (!tracePropagationB3Padding()) {
      return id.toLowerCase();
    }
    return padHexLower(id, size);
  }

  private static String padHexLower(String hex, int size) {
    String lower = hex.toLowerCase();
    int diff = size - lower.length();
    if (diff <= 0) {
      return lower;
    }
    StringBuilder sb = new StringBuilder(size);
    for (int i = 0; i < diff; i++) {
      sb.append('0');
    }
    sb.append(lower);
    return sb.toString();
  }

  private static String trimHex(String hex) {
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

  @TableTest({
    "scenario          | traceId | spanId | samplingPriority              | expectedSamplingPriority     ",
    "unset             | 1       | 2      | PrioritySampling.UNSET        |                              ",
    "sampler keep      | 2       | 3      | PrioritySampling.SAMPLER_KEEP | PrioritySampling.SAMPLER_KEEP",
    "sampler drop      | 4       | 5      | PrioritySampling.SAMPLER_DROP | PrioritySampling.SAMPLER_DROP",
    "user keep         | 5       | 6      | PrioritySampling.USER_KEEP    | PrioritySampling.SAMPLER_KEEP",
    "user drop         | 6       | 7      | PrioritySampling.USER_DROP    | PrioritySampling.SAMPLER_DROP",
    "uint64 max unset  | -1      | -2     | PrioritySampling.UNSET        |                              ",
    "uint64 max-1 keep | -2      | -1     | PrioritySampling.SAMPLER_KEEP | PrioritySampling.SAMPLER_KEEP"
  })
  @SuppressWarnings("unchecked")
  void injectHttpHeaders(
      long traceId,
      long spanId,
      @ConvertWith(PrioritySamplingConverter.class) int samplingPriority,
      @ConvertWith(PrioritySamplingConverter.class) Byte expectedSamplingPriority) {
    HttpCodec.Injector injector = B3HttpCodec.newCombinedInjector(tracePropagationB3Padding());
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    DDSpanContext mockedContext =
        mockedContext(tracer, DDTraceId.from(traceId), spanId, samplingPriority);
    Map<String, String> carrier = mock(Map.class);
    String traceIdHex = idOrPadded(traceId, 32);
    String spanIdHex = idOrPadded(spanId, 16);

    injector.inject(mockedContext, carrier, MAP_SETTER);

    verify(carrier).put(TRACE_ID_KEY, traceIdHex);
    verify(carrier).put(SPAN_ID_KEY, spanIdHex);
    if (expectedSamplingPriority != null) {
      verify(carrier).put(SAMPLING_PRIORITY_KEY, expectedSamplingPriority.toString());
      verify(carrier).put(B3_KEY, traceIdHex + "-" + spanIdHex + "-" + expectedSamplingPriority);
    } else {
      verify(carrier).put(B3_KEY, traceIdHex + "-" + spanIdHex);
    }
    verifyNoMoreInteractions(carrier);
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
  @SuppressWarnings("unchecked")
  void injectHttpHeadersWithExtractedOriginal(String traceId, String spanId) {
    HttpCodec.Injector injector = B3HttpCodec.newCombinedInjector(tracePropagationB3Padding());
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(TRACE_ID_KEY.toUpperCase(), traceId);
    headers.put(SPAN_ID_KEY.toUpperCase(), spanId);
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create().setHeaderTags(emptyMap()).setBaggageMapping(emptyMap()).apply();
    HttpCodec.Extractor extractor =
        B3HttpCodec.newExtractor(Config.get(), () -> dynamicConfig.captureTraceConfig());
    TagContext context = extractor.extract(headers, stringValuesMap());
    DDSpanContext mockedContext = mockedContext(tracer, context);
    Map<String, String> carrier = mock(Map.class);
    String traceIdHex = idOrPadded(traceId, 32);
    String spanIdHex = idOrPadded(trimHex(spanId), 16);

    injector.inject(mockedContext, carrier, MAP_SETTER);

    verify(carrier).put(TRACE_ID_KEY, traceIdHex);
    verify(carrier).put(SPAN_ID_KEY, spanIdHex);
    verify(carrier).put(B3_KEY, traceIdHex + "-" + spanIdHex);
    verifyNoMoreInteractions(carrier);
  }

  static DDSpanContext mockedContext(CoreTracer tracer, TagContext context) {
    return mockedContext(tracer, context.getTraceId(), context.getSpanId(), UNSET);
  }

  static DDSpanContext mockedContext(
      CoreTracer tracer, DDTraceId traceId, long spanId, int samplingPriority) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k1", "v1");
    baggage.put("k2", "v2");
    return new DDSpanContext(
        traceId,
        spanId,
        DDSpanId.ZERO,
        null,
        "fakeService",
        "fakeOperation",
        "fakeResource",
        samplingPriority,
        "fakeOrigin",
        baggage,
        false,
        "fakeType",
        0,
        tracer.createTraceCollector(DDTraceId.ONE),
        null,
        null,
        NoopPathwayContext.INSTANCE,
        false,
        PropagationTags.factory().empty());
  }
}
