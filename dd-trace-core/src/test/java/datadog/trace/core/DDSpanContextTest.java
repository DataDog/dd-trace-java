package datadog.trace.core;

import static datadog.trace.api.DDTags.DJM_ENABLED;
import static datadog.trace.api.DDTags.DSM_ENABLED;
import static datadog.trace.api.DDTags.PROFILING_CONTEXT_ENGINE;
import static datadog.trace.api.DDTags.PROFILING_ENABLED;
import static datadog.trace.api.DDTags.THREAD_ID;
import static datadog.trace.api.DDTags.THREAD_NAME;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.api.sampling.SamplingMechanism.SPAN_SAMPLING_RATE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.core.DDSpanContext.SPAN_KIND_VALUES;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MAX_PER_SECOND_TAG;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_RULE_RATE_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.ServiceNameSources;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.junit.utils.tabletest.TableTestTypeConverters;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(TableTestTypeConverters.class)
public class DDSpanContextTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;
  private ProfilingContextIntegration profilingContextIntegration;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    profilingContextIntegration = mock(ProfilingContextIntegration.class);
    tracer =
        tracerBuilder()
            .writer(writer)
            .profilingContextIntegration(profilingContextIntegration)
            .build();
  }

  @ParameterizedTest
  @ValueSource(strings = {DDTags.SERVICE_NAME, DDTags.RESOURCE_NAME, DDTags.SPAN_TYPE, "some.tag"})
  void nullValuesForTagsDeleteExistingTags(String name) throws Exception {
    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType")
            .start();
    DDSpanContext context = (DDSpanContext) span.spanContext();

    context.setTag("some.tag", "asdf");
    context.setTag(name, null);
    context.setErrorFlag(true, ErrorPriorities.DEFAULT);
    span.finish();
    writer.waitForTraces(1);

    Map<String, Object> expectedTags = createExpectedTagsFromCurrentThread();
    if (!"some.tag".equals(name)) {
      expectedTags.put("some.tag", "asdf");
    }

    assertTagmap(context.getTags(), expectedTags);
    assertEquals("fakeService", context.getServiceName());
    assertEquals("fakeResource", context.getResourceName().toString());
    assertEquals("fakeType", context.getSpanType().toString());
  }

  private static Map<String, Object> createExpectedTagsFromCurrentThread() {
    Thread thread = Thread.currentThread();
    Map<String, Object> expectedTags = new HashMap<>();
    expectedTags.put(THREAD_NAME, thread.getName());
    expectedTags.put(THREAD_ID, thread.getId());
    expectedTags.put(DDTags.DD_SVC_SRC, ServiceNameSources.MANUAL);
    return expectedTags;
  }

  // spotless:off
  @TableTest({
    "scenario          | name                          | expected              | method       ",
    "SERVICE_NAME tag  | " + DDTags.SERVICE_NAME   + " | different service  | serviceName  ",
    "RESOURCE_NAME tag | " + DDTags.RESOURCE_NAME  + " | different resource | resourceName ",
    "SPAN_TYPE tag     | " + DDTags.SPAN_TYPE      + " | different type     | spanType     "
  })
  //spotless:on
  void specialTagsSetCertainValues(String scenario, String name, String expected, String method)
      throws Exception {
    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType")
            .start();
    DDSpanContext context = (DDSpanContext) span.spanContext();

    context.setTag(name, expected);
    span.finish();
    writer.waitForTraces(1);

    Map<String, Object> expectedTags = createExpectedTagsFromCurrentThread();
    assertTagmap(context.getTags(), expectedTags);

    String value;
    switch (method) {
      case "serviceName":
        value = context.getServiceName();
        break;
      case "resourceName":
        value = context.getResourceName().toString();
        break;
      case "spanType":
        value = context.getSpanType().toString();
        break;
      default:
        throw new IllegalArgumentException("Unknown method: " + method);
    }
    assertEquals(expected, value);
  }

  static Object[][] tagsCanBeAddedToContextArguments() {
    return new Object[][] {
      {"tag.name", "some value"},
      {"tag with int", 1234},
      {"tag-with-bool", false},
      {"tag_with_float", 0.321}
    };
  }

  @ParameterizedTest
  @MethodSource("tagsCanBeAddedToContextArguments")
  void tagsCanBeAddedToContext(String name, Object value) throws Exception {
    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType")
            .start();
    DDSpanContext context = (DDSpanContext) span.spanContext();

    context.setTag(name, value);
    span.finish();
    writer.waitForTraces(1);

    Map<String, Object> expectedTags = createExpectedTagsFromCurrentThread();
    expectedTags.put(name, value);
    assertTagmap(context.getTags(), expectedTags);
  }

  @TableTest({
    "expectedType      | value            ",
    "java.lang.Integer | 0                ",
    "java.lang.Integer | Integer.MAX_VALUE",
    "java.lang.Integer | Integer.MIN_VALUE",
    "java.lang.Short   | Short.MAX_VALUE  ",
    "java.lang.Short   | Short.MIN_VALUE  ",
    "java.lang.Float   | Float.MAX_VALUE  ",
    "java.lang.Float   | Float.MIN_VALUE  ",
    "java.lang.Double  | Double.MAX_VALUE ",
    "java.lang.Double  | Double.MIN_VALUE ",
    "java.lang.Float   | 1f               ",
    "java.lang.Double  | 1d               ",
    "java.lang.Float   | 0.5f             ",
    "java.lang.Double  | 0.5d             ",
    "java.lang.Integer | 0x55             "
  })
  void metricsUseExpectedTypes(Class<?> expectedType, Number value) {
    // floats should be converted to doubles.
    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.spanContext();

    context.setMetric("test", value);

    assertInstanceOf(expectedType, context.getTag("test"));

    span.finish();
  }

  @Test
  void forceKeepReallyKeepsTrace() {
    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.spanContext();

    context.setSamplingPriority(SAMPLER_DROP, DEFAULT);
    assertEquals(SAMPLER_DROP, context.getSamplingPriority());

    context.lockSamplingPriority();
    assertFalse(context.setSamplingPriority(USER_DROP, MANUAL));
    assertEquals(SAMPLER_DROP, context.getSamplingPriority());

    context.forceKeep();
    assertEquals(USER_KEEP, context.getSamplingPriority());

    span.finish();
  }

  @Test
  void setTraceSegmentTagsAndDataOnCorrectSpan() {
    ExtractedContext extracted =
        (ExtractedContext)
            new ExtractedContext(
                    DDTraceId.from(123),
                    456,
                    SAMPLER_KEEP,
                    "789",
                    tracer.getPropagationTagsFactory().empty(),
                    DATADOG)
                .withRequestContextDataAppSec("dummy");

    AgentSpan top =
        tracer.buildSpan("datadog", "top").asChildOf((AgentSpanContext) extracted).start();
    DDSpanContext topC = (DDSpanContext) top.spanContext();
    TraceSegment topTS = top.getRequestContext().getTraceSegment();

    AgentSpan current = tracer.buildSpan("datadog", "current").asChildOf(top.spanContext()).start();
    TraceSegment currentTS = current.getRequestContext().getTraceSegment();
    DDSpanContext currentC = (DDSpanContext) current.spanContext();

    currentTS.setDataTop("ctd", "[1]");
    currentTS.setTagTop("ctt", "t1");
    currentTS.setDataCurrent("ccd", "[2]");
    currentTS.setTagCurrent("cct", "t2");
    topTS.setDataTop("ttd", "[3]");
    topTS.setTagTop("ttt", "t3");
    topTS.setDataCurrent("tcd", "[4]");
    topTS.setTagCurrent("tct", "t4");

    Map<String, Object> expectedTopTags = new HashMap<>();
    expectedTopTags.put(dataTag("ctd"), "[1]");
    expectedTopTags.put("ctt", "t1");
    expectedTopTags.put(dataTag("ttd"), "[3]");
    expectedTopTags.put("ttt", "t3");
    expectedTopTags.put(dataTag("tcd"), "[4]");
    expectedTopTags.put("tct", "t4");
    assertTagmap(topC.getTags(), expectedTopTags, true);

    Map<String, Object> expectedCurrentTags = new HashMap<>();
    expectedCurrentTags.put(dataTag("ccd"), "[2]");
    expectedCurrentTags.put("cct", "t2");
    assertTagmap(currentC.getTags(), expectedCurrentTags, true);

    current.finish();
    top.finish();
  }

  @TableTest({
    "rate | limit            ",
    "1.0  | 10               ",
    "0.5  | 100              ",
    "0.25 | Integer.MAX_VALUE"
  })
  void setSingleSpanSamplingTags(double rate, int limit) {
    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.spanContext();
    assertEquals(UNSET, context.getSamplingPriority());

    context.setSpanSamplingPriority(rate, limit);

    assertEquals((int) SPAN_SAMPLING_RATE, context.getTag(SPAN_SAMPLING_MECHANISM_TAG));
    assertEquals(rate, context.getTag(SPAN_SAMPLING_RULE_RATE_TAG));
    assertEquals(
        limit == Integer.MAX_VALUE ? null : Double.valueOf(limit),
        context.getTag(SPAN_SAMPLING_MAX_PER_SECOND_TAG));
    // single span sampling should not change the trace sampling priority
    assertEquals(UNSET, context.getSamplingPriority());
    // make sure the `_dd.p.dm` tag has not been set by single span sampling
    assertFalse(context.getPropagationTags().createTagMap().containsKey("_dd.p.dm"));

    span.finish();
  }

  @Test
  void settingResourceNameToNullIsIgnored() {
    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();

    span.setResourceName(null);

    assertEquals("fakeResource", ((DDSpan) span).getResourceName().toString());

    span.finish();
  }

  @Test
  void settingOperationNameTriggersConstantEncoding() {
    when(profilingContextIntegration.encodeOperationName("fakeOperation")).thenReturn(1);
    when(profilingContextIntegration.encodeResourceName("fakeResource")).thenReturn(-1);

    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();

    verify(profilingContextIntegration, times(1)).encodeOperationName("fakeOperation");
    verify(profilingContextIntegration, times(1)).encodeResourceName("fakeResource");
    assertEquals(1, ((DDSpanContext) span.spanContext()).getEncodedOperationName());
    assertEquals(-1, ((DDSpanContext) span.spanContext()).getEncodedResourceName());

    when(profilingContextIntegration.encodeOperationName("newOperationName")).thenReturn(2);
    span.setOperationName("newOperationName");

    verify(profilingContextIntegration, times(1)).encodeOperationName("newOperationName");
    assertEquals(2, ((DDSpanContext) span.spanContext()).getEncodedOperationName());

    when(profilingContextIntegration.encodeResourceName("newResourceName")).thenReturn(-2);
    span.setResourceName("newResourceName");

    verify(profilingContextIntegration, times(1)).encodeResourceName("newResourceName");
    assertEquals(-2, ((DDSpanContext) span.spanContext()).getEncodedResourceName());

    span.finish();
  }

  @Test
  void spanIdsPrintedAsUnsignedLong() {
    AgentSpan parent =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanId(-987654321)
            .start();

    AgentSpan span =
        tracer
            .buildSpan("datadog", "fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanId(-123456789)
            .asChildOf(parent.spanContext())
            .start();

    DDSpanContext context = (DDSpanContext) span.spanContext();

    // even though span ID and parent ID are setup as negative numbers, they should be printed as
    // their unsigned value
    // asserting there is no negative sign after ids is the best I can do.
    assertFalse(context.toString().contains("id=-"));

    span.finish();
    parent.finish();
  }

  @Test
  void serviceNameSourceIsPropagatedFromParentToChildSpan() {
    AgentSpan parent =
        tracer.buildSpan("datadog", "parentOperation").withServiceName("fakeService").start();

    AgentSpan child =
        tracer.buildSpan("datadog", "childOperation").asChildOf(parent.spanContext()).start();
    DDSpanContext childContext = (DDSpanContext) child.spanContext();

    assertEquals(ServiceNameSources.MANUAL, childContext.getServiceNameSource());

    child.finish();
    parent.finish();
  }

  @Test
  void spanKindOrdinalConstantsAndSpanKindValuesArrayStayInSync() {
    // SPAN_KIND_VALUES array covers all ordinals
    assertEquals(DDSpanContext.SPAN_KIND_CUSTOM + 1, SPAN_KIND_VALUES.length);

    // each known ordinal maps to the correct Tags constant"
    assertEquals(Tags.SPAN_KIND_SERVER, SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_SERVER]);
    assertEquals(Tags.SPAN_KIND_CLIENT, SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_CLIENT]);
    assertEquals(Tags.SPAN_KIND_PRODUCER, SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_PRODUCER]);
    assertEquals(Tags.SPAN_KIND_CONSUMER, SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_CONSUMER]);
    assertEquals(Tags.SPAN_KIND_INTERNAL, SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_INTERNAL]);
    assertEquals(Tags.SPAN_KIND_BROKER, SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_BROKER]);

    // UNSET and CUSTOM map to null
    assertNull(SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_UNSET]);
    assertNull(SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_CUSTOM]);
  }

  @TableTest({
    "scenario | kindString | expectedOrdinal                 ",
    "server   | server     | DDSpanContext.SPAN_KIND_SERVER  ",
    "client   | client     | DDSpanContext.SPAN_KIND_CLIENT  ",
    "producer | producer   | DDSpanContext.SPAN_KIND_PRODUCER",
    "consumer | consumer   | DDSpanContext.SPAN_KIND_CONSUMER",
    "internal | internal   | DDSpanContext.SPAN_KIND_INTERNAL",
    "broker   | broker     | DDSpanContext.SPAN_KIND_BROKER  "
  })
  void setSpanKindOrdinalRoundTripsWithSpanKindValues(
      String scenario, String kindString, int expectedOrdinal) {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    DDSpanContext context = (DDSpanContext) span.spanContext();
    context.setSpanKindOrdinal(kindString);

    assertEquals(expectedOrdinal, context.getSpanKindOrdinal());
    assertEquals(kindString, SPAN_KIND_VALUES[expectedOrdinal]);

    span.finish();
  }

  @Test
  void builderLedgerRemovalOfSpanKindClearsCachedOrdinal() {
    // Setting then nulling span.kind on the same builder routes through
    // setAllTags(TagMap.Ledger) at construction time. The removal path must
    // keep the cached ordinal in sync with unsafeTags, otherwise eligibility
    // checks that read the cached byte see a stale kind.
    AgentSpan span =
        tracer
            .buildSpan("datadog", "test")
            .withTag(SPAN_KIND, Tags.SPAN_KIND_CLIENT)
            .withTag(SPAN_KIND, (Object) null)
            .start();
    DDSpanContext context = (DDSpanContext) span.spanContext();

    assertNull(context.getTag(SPAN_KIND));
    assertEquals(DDSpanContext.SPAN_KIND_UNSET, context.getSpanKindOrdinal());

    span.finish();
  }

  @TypeConverter
  public static int toInt(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }
    switch (value) {
      case "DDSpanContext.SPAN_KIND_SERVER":
        return DDSpanContext.SPAN_KIND_SERVER;
      case "DDSpanContext.SPAN_KIND_CLIENT":
        return DDSpanContext.SPAN_KIND_CLIENT;
      case "DDSpanContext.SPAN_KIND_PRODUCER":
        return DDSpanContext.SPAN_KIND_PRODUCER;
      case "DDSpanContext.SPAN_KIND_CONSUMER":
        return DDSpanContext.SPAN_KIND_CONSUMER;
      case "DDSpanContext.SPAN_KIND_BROKER":
        return DDSpanContext.SPAN_KIND_BROKER;
      case "DDSpanContext.SPAN_KIND_INTERNAL":
        return DDSpanContext.SPAN_KIND_INTERNAL;
      default:
        return TableTestTypeConverters.toInt(value);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        Tags.SPAN_KIND_SERVER,
        Tags.SPAN_KIND_CLIENT,
        Tags.SPAN_KIND_PRODUCER,
        Tags.SPAN_KIND_CONSUMER,
        Tags.SPAN_KIND_INTERNAL,
        Tags.SPAN_KIND_BROKER
      })
  void setTagAndGetTagRoundTripForSpanKind(String kindString) {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    span.setTag(SPAN_KIND, kindString);

    assertEquals(kindString, span.getTag(SPAN_KIND));

    span.finish();
  }

  @Test
  void getTagReturnsNullWhenSpanKindNotSet() {
    AgentSpan span = tracer.buildSpan("test", "test").start();

    assertNull(span.getTag(SPAN_KIND));

    span.finish();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        Tags.SPAN_KIND_SERVER,
        Tags.SPAN_KIND_CLIENT,
        Tags.SPAN_KIND_PRODUCER,
        Tags.SPAN_KIND_CONSUMER,
        Tags.SPAN_KIND_INTERNAL,
        Tags.SPAN_KIND_BROKER
      })
  void setTagThenRemoveTagClearsSpanKind(String kindString) {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    span.setTag(SPAN_KIND, kindString);

    assertEquals(kindString, span.getTag(SPAN_KIND));

    ((DDSpan) span).spanContext().removeTag(SPAN_KIND);

    assertNull(span.getTag(SPAN_KIND));

    span.finish();
  }

  @Test
  void setTagWithCustomSpanKindFallsBackToTagMap() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    span.setTag(SPAN_KIND, "custom-kind");

    assertEquals("custom-kind", span.getTag(SPAN_KIND));

    span.finish();
  }

  static void assertTagmap(Map<?, ?> source, Map<?, ?> comparison) {
    assertTagmap(source, comparison, false);
  }

  static void assertTagmap(Map<?, ?> source, Map<?, ?> comparison, boolean removeThread) {
    Map<Object, Object> sourceWithoutCommonTags = new HashMap<>(source);
    sourceWithoutCommonTags.remove("runtime-id");
    sourceWithoutCommonTags.remove("language");
    sourceWithoutCommonTags.remove("_dd.agent_psr");
    sourceWithoutCommonTags.remove("_sample_rate");
    sourceWithoutCommonTags.remove("process_id");
    sourceWithoutCommonTags.remove("_dd.trace_span_attribute_schema");
    sourceWithoutCommonTags.remove(PROFILING_ENABLED);
    sourceWithoutCommonTags.remove(PROFILING_CONTEXT_ENGINE);
    sourceWithoutCommonTags.remove(DSM_ENABLED);
    sourceWithoutCommonTags.remove(DJM_ENABLED);
    if (removeThread) {
      sourceWithoutCommonTags.remove(THREAD_ID);
      sourceWithoutCommonTags.remove(THREAD_NAME);
    }
    assertEquals(comparison, sourceWithoutCommonTags);
  }

  private static String dataTag(String tag) {
    return "_dd." + tag + ".json";
  }
}
