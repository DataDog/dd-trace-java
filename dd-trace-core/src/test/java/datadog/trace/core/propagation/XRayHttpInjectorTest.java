package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.core.CoreTracer.TRACE_ID_MAX;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.datastreams.DataStreamsMonitoring;
import datadog.trace.core.test.DDCoreSpecification;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class XRayHttpInjectorTest extends DDCoreSpecification {

  HttpCodec.Injector injector =
      XRayHttpCodec.newInjector(Collections.singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));

  static Stream<Arguments> injectHttpHeadersArguments() {
    String maxStr = TRACE_ID_MAX.toString();
    String maxMinus1Str = TRACE_ID_MAX.subtract(BigInteger.ONE).toString();
    return Stream.of(
        Arguments.of(
            "1",
            "2",
            UNSET,
            "Root=1-633c7675-000000000000000000000001;Parent=0000000000000002;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            "2",
            "3",
            SAMPLER_KEEP,
            "Root=1-633c7675-000000000000000000000002;Parent=0000000000000003;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            "4",
            "5",
            SAMPLER_DROP,
            "Root=1-633c7675-000000000000000000000004;Parent=0000000000000005;Sampled=0;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            "5",
            "6",
            USER_KEEP,
            "Root=1-633c7675-000000000000000000000005;Parent=0000000000000006;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            "6",
            "7",
            USER_DROP,
            "Root=1-633c7675-000000000000000000000006;Parent=0000000000000007;Sampled=0;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            maxStr,
            TRACE_ID_MAX.subtract(BigInteger.ONE).toString(),
            UNSET,
            "Root=1-633c7675-00000000ffffffffffffffff;Parent=fffffffffffffffe;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            maxMinus1Str,
            maxStr,
            SAMPLER_KEEP,
            "Root=1-633c7675-00000000fffffffffffffffe;Parent=ffffffffffffffff;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersArguments")
  void injectHttpHeaders(
      String traceId, String spanId, int samplingPriority, String expectedTraceHeader)
      throws Exception {
    ListWriter writer = new ListWriter();
    TimeSource timeSource = mock(TimeSource.class);
    DataStreamsMonitoring dsm = mock(DataStreamsMonitoring.class);
    CoreTracer tracer =
        tracerBuilder().dataStreamsMonitoring(dsm).writer(writer).timeSource(timeSource).build();
    try {
      Map<String, String> baggage = new HashMap<>();
      baggage.put("k", "v");
      baggage.put("some-baggage-key", "some-value");
      DDSpanContext mockedContext =
          new DDSpanContext(
              DDTraceId.from(traceId),
              DDSpanId.from(spanId),
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
              null);

      Map<String, String> carrier = mock(Map.class);

      when(timeSource.getCurrentTimeMillis()).thenReturn(1_664_906_869_196L);

      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(timeSource, times(1)).getCurrentTimeMillis();
      verify(carrier, times(1)).put("X-Amzn-Trace-Id", expectedTraceHeader);
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> injectHttpHeadersWithExtractedOriginalArguments() {
    return Stream.of(
        Arguments.of(
            "00001",
            "00001",
            "Root=1-633c7675-000000000000000000000001;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            "463ac35c9f6413ad",
            "463ac35c9f6413ad",
            "Root=1-633c7675-00000000463ac35c9f6413ad;Parent=463ac35c9f6413ad;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            "48485a3953bb6124",
            "1",
            "Root=1-633c7675-0000000048485a3953bb6124;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            repeat("f", 16),
            "1",
            "Root=1-633c7675-00000000ffffffffffffffff;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            repeat("a", 8) + repeat("f", 8),
            "1",
            "Root=1-633c7675-00000000aaaaaaaaffffffff;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"),
        Arguments.of(
            "1",
            repeat("f", 16),
            "Root=1-633c7675-000000000000000000000001;Parent=ffffffffffffffff;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v"));
  }

  @ParameterizedTest
  @MethodSource("injectHttpHeadersWithExtractedOriginalArguments")
  void injectHttpHeadersWithExtractedOriginal(
      String traceId, String spanId, String expectedTraceHeader) throws Exception {
    ListWriter writer = new ListWriter();
    TimeSource timeSource = mock(TimeSource.class);
    DataStreamsMonitoring dsm = mock(DataStreamsMonitoring.class);
    CoreTracer tracer =
        tracerBuilder().dataStreamsMonitoring(dsm).writer(writer).timeSource(timeSource).build();
    try {
      String paddedTraceId = padLeft(traceId, 16, '0');
      String paddedSpanId = padLeft(spanId, 16, '0');
      Map<String, String> headers =
          Collections.singletonMap(
              "X-Amzn-Trace-Id",
              "Root=1-00000000-00000000" + paddedTraceId + ";Parent=" + paddedSpanId);
      DynamicConfig dynamicConfig =
          DynamicConfig.create()
              .setHeaderTags(new HashMap<String, String>())
              .setBaggageMapping(new HashMap<String, String>())
              .apply();
      HttpCodec.Extractor extractor =
          XRayHttpCodec.newExtractor(Config.get(), () -> dynamicConfig.captureTraceConfig());
      TagContext context = extractor.extract(headers, ContextVisitors.stringValuesMap());

      Map<String, String> baggage = new HashMap<>();
      baggage.put("k", "v");
      baggage.put("some-baggage-key", "some-value");
      DDSpanContext mockedContext =
          new DDSpanContext(
              context.getTraceId(),
              context.getSpanId(),
              DDSpanId.ZERO,
              null,
              "fakeService",
              "fakeOperation",
              "fakeResource",
              UNSET,
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
              null);

      Map<String, String> carrier = mock(Map.class);

      when(timeSource.getCurrentTimeMillis()).thenReturn(1_664_906_869_196L);

      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(timeSource, times(1)).getCurrentTimeMillis();
      verify(carrier, times(1)).put("X-Amzn-Trace-Id", expectedTraceHeader);
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  @Test
  void injectHttpHeadersWithEndToEnd() throws Exception {
    ListWriter writer = new ListWriter();
    TimeSource timeSource = mock(TimeSource.class);
    DataStreamsMonitoring dsm = mock(DataStreamsMonitoring.class);
    CoreTracer tracer =
        tracerBuilder().dataStreamsMonitoring(dsm).writer(writer).timeSource(timeSource).build();
    clearInvocations(timeSource);
    try {
      Map<String, String> baggage = new HashMap<>();
      baggage.put("k", "v");
      DDSpanContext mockedContext =
          new DDSpanContext(
              DDTraceId.from("1"),
              DDSpanId.from("2"),
              DDSpanId.ZERO,
              null,
              "fakeService",
              "fakeOperation",
              "fakeResource",
              UNSET,
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
              null);

      Map<String, String> carrier = mock(Map.class);

      when(timeSource.getCurrentTimeNanos()).thenReturn(1_664_906_869_196_787_813L);
      when(timeSource.getNanoTicks()).thenReturn(1_664_906_869_196L);

      mockedContext.beginEndToEnd();
      injector.inject(mockedContext, carrier, MapSetter.INSTANCE);

      verify(timeSource, times(1)).getCurrentTimeNanos();
      verify(timeSource, times(1)).getNanoTicks();
      verify(carrier, times(1))
          .put(
              "X-Amzn-Trace-Id",
              "Root=1-633c7675-000000000000000000000001;Parent=0000000000000002;_dd.origin=fakeOrigin;t0=1664906869195;k=v");
      verifyNoMoreInteractions(carrier);
    } finally {
      tracer.close();
    }
  }

  static String padLeft(String s, int length, char pad) {
    if (s.length() >= length) {
      return s;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = s.length(); i < length; i++) {
      sb.append(pad);
    }
    sb.append(s);
    return sb.toString();
  }

  static String repeat(String s, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(s);
    }
    return sb.toString();
  }
}
