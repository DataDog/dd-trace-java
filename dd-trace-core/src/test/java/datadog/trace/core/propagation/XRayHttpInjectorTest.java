package datadog.trace.core.propagation;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.bootstrap.instrumentation.api.ContextVisitors.stringValuesMap;
import static datadog.trace.core.propagation.HttpCodecTestHelper.headers;
import static datadog.trace.core.propagation.XRayHttpCodec.X_AMZN_TRACE_ID;
import static datadog.trace.core.propagation.XRayTestHelper.zeroPadId;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.time.TimeSource;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.datastreams.DataStreamsMonitoring;
import datadog.trace.junit.utils.tabletest.PrioritySamplingConverter;
import datadog.trace.junit.utils.tabletest.TraceIdConverter;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class XRayHttpInjectorTest extends DDCoreJavaSpecification {
  private HttpCodec.Injector injector;
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    this.injector =
        XRayHttpCodec.newInjector(singletonMap("some-baggage-key", "SOME_CUSTOM_HEADER"));
    TimeSource timeSource = mock(TimeSource.class);
    when(timeSource.getCurrentTimeMillis()).thenReturn(1_664_906_869_196L);
    when(timeSource.getCurrentTimeNanos()).thenReturn(1_664_906_869_196_787_813L);
    when(timeSource.getNanoTicks()).thenReturn(1_664_906_869_196L);
    this.tracer =
        tracerBuilder()
            .dataStreamsMonitoring(mock(DataStreamsMonitoring.class))
            .writer(new ListWriter())
            .timeSource(timeSource)
            .build();
  }

  @AfterEach
  void tearDown() {
    this.tracer.close();
  }

  @TableTest({
    "scenario         | traceId        | spanId         | samplingPriority              | expectedTraceHeader                                                                                                                 ",
    "unset 1->2       | 1              | 2              | PrioritySampling.UNSET        | 'Root=1-633c7675-000000000000000000000001;Parent=0000000000000002;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'          ",
    "keep 2->3        | 2              | 3              | PrioritySampling.SAMPLER_KEEP | 'Root=1-633c7675-000000000000000000000002;Parent=0000000000000003;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "drop 4->5        | 4              | 5              | PrioritySampling.SAMPLER_DROP | 'Root=1-633c7675-000000000000000000000004;Parent=0000000000000005;Sampled=0;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "user keep 5->6   | 5              | 6              | PrioritySampling.USER_KEEP    | 'Root=1-633c7675-000000000000000000000005;Parent=0000000000000006;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "user drop 6->7   | 6              | 7              | PrioritySampling.USER_DROP    | 'Root=1-633c7675-000000000000000000000006;Parent=0000000000000007;Sampled=0;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "unset max->max-1 | TRACE_ID_MAX   | TRACE_ID_MAX-1 | PrioritySampling.UNSET        | 'Root=1-633c7675-00000000ffffffffffffffff;Parent=fffffffffffffffe;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'          ",
    "keep max-1->max  | TRACE_ID_MAX-1 | TRACE_ID_MAX   | PrioritySampling.SAMPLER_KEEP | 'Root=1-633c7675-00000000fffffffffffffffe;Parent=ffffffffffffffff;Sampled=1;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'"
  })
  void injectHttpHeaders(
      @ConvertWith(TraceIdConverter.class) String traceId,
      @ConvertWith(TraceIdConverter.class) String spanId,
      @ConvertWith(PrioritySamplingConverter.class) byte samplingPriority,
      String expectedTraceHeader) {
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k", "v");
    baggage.put("some-baggage-key", "some-value");
    DDSpanContext spanContext =
        createContext(DDTraceId.from(traceId), DDSpanId.from(spanId), samplingPriority, baggage);
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    assertEquals(1, carrier.size());
    assertEquals(expectedTraceHeader, carrier.get(X_AMZN_TRACE_ID));
  }

  @TableTest({
    "scenario       | traceId          | spanId           | expectedTraceHeader                                                                                                       ",
    "short ids      | 00001            | 00001            | 'Root=1-633c7675-000000000000000000000001;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "long ids same  | 463ac35c9f6413ad | 463ac35c9f6413ad | 'Root=1-633c7675-00000000463ac35c9f6413ad;Parent=463ac35c9f6413ad;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "long trace     | 48485a3953bb6124 | 1                | 'Root=1-633c7675-0000000048485a3953bb6124;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "max trace id   | ffffffffffffffff | 1                | 'Root=1-633c7675-00000000ffffffffffffffff;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "mixed trace id | aaaaaaaaffffffff | 1                | 'Root=1-633c7675-00000000aaaaaaaaffffffff;Parent=0000000000000001;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'",
    "max span id    | 1                | ffffffffffffffff | 'Root=1-633c7675-000000000000000000000001;Parent=ffffffffffffffff;_dd.origin=fakeOrigin;SOME_CUSTOM_HEADER=some-value;k=v'"
  })
  void injectHttpHeadersWithExtractedOriginal(
      String traceId, String spanId, String expectedTraceHeader) {
    // spotless:off
    Map<String, String> headers = headers(
        X_AMZN_TRACE_ID,  "Root=1-00000000-00000000" + zeroPadId(traceId) + ";Parent=" + zeroPadId(spanId)
    );
    // spotless:on
    DynamicConfig<DynamicConfig.Snapshot> dynamicConfig =
        DynamicConfig.create().setHeaderTags(emptyMap()).setBaggageMapping(emptyMap()).apply();
    HttpCodec.Extractor extractor =
        XRayHttpCodec.newExtractor(Config.get(), dynamicConfig::captureTraceConfig);
    TagContext context = extractor.extract(headers, stringValuesMap());
    Map<String, String> baggage = new HashMap<>();
    baggage.put("k", "v");
    baggage.put("some-baggage-key", "some-value");
    DDSpanContext spanContext =
        createContext(context.getTraceId(), context.getSpanId(), UNSET, baggage);
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    assertEquals(1, carrier.size());
    assertEquals(expectedTraceHeader, carrier.get(X_AMZN_TRACE_ID));
  }

  @Test
  void injectHttpHeadersWithEndToEnd() {
    DDSpanContext spanContext =
        createContext(DDTraceId.from("1"), DDSpanId.from("2"), UNSET, singletonMap("k", "v"));
    spanContext.beginEndToEnd();
    Map<String, String> carrier = new HashMap<>();

    this.injector.inject(spanContext, carrier, Map::put);

    String expectedTraceHeader =
        "Root=1-633c7675-000000000000000000000001;Parent=0000000000000002;_dd.origin=fakeOrigin;t0=1664906869196;k=v";
    assertEquals(1, carrier.size());
    assertEquals(expectedTraceHeader, carrier.get(X_AMZN_TRACE_ID));
  }

  private DDSpanContext createContext(
      DDTraceId traceId, long spanId, int samplingPriority, Map<String, String> baggage) {
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
        this.tracer.createTraceCollector(DDTraceId.ONE),
        null,
        null,
        NoopPathwayContext.INSTANCE,
        false,
        null);
  }
}
