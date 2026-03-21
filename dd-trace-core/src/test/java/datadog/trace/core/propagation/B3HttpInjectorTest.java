package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static datadog.trace.core.propagation.B3HttpCodec.B3_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SAMPLING_PRIORITY_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.SPAN_ID_KEY;
import static datadog.trace.core.propagation.B3HttpCodec.TRACE_ID_KEY;
import static datadog.trace.test.util.StringUtils.trimHex;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.test.DDCoreSpecification;
import datadog.trace.test.util.StringUtils;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class B3HttpInjectorTest extends DDCoreSpecification {

  boolean tracePropagationB3Padding() {
    return false;
  }

  String idOrPadded(BigInteger id, int size) {
    return idOrPadded(id.toString(16), size);
  }

  String idOrPadded(String id, int size) {
    if (!tracePropagationB3Padding()) {
      return id.toLowerCase();
    }
    return StringUtils.padHexLower(id, size);
  }

  static Stream<Arguments> injectHttpHeadersArguments() {
    String maxHex = TRACE_ID_MAX.toString(16).toLowerCase();
    String maxMinus1Hex = TRACE_ID_MAX.subtract(BigInteger.ONE).toString(16).toLowerCase();
    return Stream.of(
        Arguments.of("1", "2", (int) UNSET, null),
        Arguments.of("2", "3", (int) SAMPLER_KEEP, (int) SAMPLER_KEEP),
        Arguments.of("4", "5", (int) SAMPLER_DROP, (int) SAMPLER_DROP),
        Arguments.of("5", "6", (int) USER_KEEP, (int) SAMPLER_KEEP),
        Arguments.of("6", "7", (int) USER_DROP, (int) SAMPLER_DROP),
        Arguments.of(maxHex, maxMinus1Hex, (int) UNSET, null),
        Arguments.of(maxMinus1Hex, maxHex, (int) SAMPLER_KEEP, (int) SAMPLER_KEEP));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersArguments")
  void injectHttpHeaders(
      String traceIdStr, String spanIdStr, int samplingPriority, Integer expectedSamplingPriority)
      throws Exception {
    HttpCodec.Injector injector = B3HttpCodec.newCombinedInjector(tracePropagationB3Padding());
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      DDTraceId traceId = DDTraceId.fromHex(traceIdStr);
      long spanId = DDSpanId.fromHex(spanIdStr);
      String traceIdHex = idOrPadded(new BigInteger(traceIdStr, 16), 32);
      String spanIdHex = idOrPadded(new BigInteger(spanIdStr, 16), 16);
      DDSpanContext mockedContext = mockedContext(tracer, traceId, spanId, samplingPriority);

      Map<String, String> carrier = mock(Map.class);
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1)).put(TRACE_ID_KEY, traceIdHex);
      verify(carrier, times(1)).put(SPAN_ID_KEY, spanIdHex);
      if (expectedSamplingPriority != null) {
        verify(carrier, times(1))
            .put(SAMPLING_PRIORITY_KEY, String.valueOf(expectedSamplingPriority));
        verify(carrier, times(1))
            .put(B3_KEY, traceIdHex + "-" + spanIdHex + "-" + expectedSamplingPriority);
      } else {
        verify(carrier, times(1)).put(B3_KEY, traceIdHex + "-" + spanIdHex);
      }
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> injectHttpHeadersWithExtractedOriginalArguments() {
    return Stream.of(
        Arguments.of("00001", "00001"),
        Arguments.of("463ac35c9f6413ad", "463ac35c9f6413ad"),
        Arguments.of("463ac35c9f6413ad48485a3953bb6124", "1"),
        Arguments.of(repeat("f", 16), "1"),
        Arguments.of(repeat("a", 16) + repeat("f", 16), "1"),
        Arguments.of("1", repeat("f", 16)),
        Arguments.of("1", "000" + repeat("f", 16)));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersWithExtractedOriginalArguments")
  void injectHttpHeadersWithExtractedOriginal(String traceIdStr, String spanIdStr)
      throws Exception {
    HttpCodec.Injector injector = B3HttpCodec.newCombinedInjector(tracePropagationB3Padding());
    ListWriter writer = new ListWriter();
    CoreTracer tracer = tracerBuilder().writer(writer).build();
    try {
      Map<String, String> headers = new HashMap<>();
      headers.put(TRACE_ID_KEY.toUpperCase(), traceIdStr);
      headers.put(SPAN_ID_KEY.toUpperCase(), spanIdStr);
      DynamicConfig dynamicConfig =
          DynamicConfig.create()
              .setHeaderTags(Collections.<String, String>emptyMap())
              .setBaggageMapping(Collections.<String, String>emptyMap())
              .apply();
      HttpCodec.Extractor extractor =
          B3HttpCodec.newExtractor(Config.get(), () -> dynamicConfig.captureTraceConfig());
      TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());
      DDSpanContext mockedContext = mockedContext(tracer, context);

      String traceIdHex = idOrPadded(traceIdStr, 32);
      String spanIdHex = idOrPadded(trimHex(spanIdStr), 16);

      Map<String, String> carrier = mock(Map.class);
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(carrier, times(1)).put(TRACE_ID_KEY, traceIdHex);
      verify(carrier, times(1)).put(SPAN_ID_KEY, spanIdHex);
      verify(carrier, times(1)).put(B3_KEY, traceIdHex + "-" + spanIdHex);
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
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

  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}

class B3HttpInjectorPaddedTest extends B3HttpInjectorTest {
  @Override
  boolean tracePropagationB3Padding() {
    return true;
  }
}
