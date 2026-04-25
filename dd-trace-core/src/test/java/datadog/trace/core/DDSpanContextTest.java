package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.api.sampling.SamplingMechanism.SPAN_SAMPLING_RATE;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MAX_PER_SECOND_TAG;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG;
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_RULE_RATE_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

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

  // spotless:off
  @TableTest({
      "scenario      | name",
      "SERVICE_NAME  | " + DDTags.SERVICE_NAME,
      "RESOURCE_NAME | " + DDTags.RESOURCE_NAME,
      "SPAN_TYPE     | " + DDTags.SPAN_TYPE,
      "spme.tag      | some.tag"
  })
  //spotless:on
  void nullValuesForTagsDeleteExistingTags(String scenario, String name) throws Exception {
    AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    context.setTag("some.tag", "asdf");
    context.setTag(name, (String) null);
    context.setErrorFlag(true, ErrorPriorities.DEFAULT);
    span.finish();
    writer.waitForTraces(1);

    Thread thread = Thread.currentThread();
    Map<String, Object> expectedTags = new HashMap<>();
    if (!name.equals("some.tag")) {
      expectedTags.put("some.tag", "asdf");
    }
    expectedTags.put(DDTags.THREAD_NAME, thread.getName());
    expectedTags.put(DDTags.THREAD_ID, thread.getId());
    expectedTags.put(DDTags.DD_SVC_SRC, ServiceNameSources.MANUAL);

    assertTagmap(context.getTags(), expectedTags);
    assertEquals("fakeService", context.getServiceName());
    assertEquals("fakeResource", context.getResourceName().toString());
    assertEquals("fakeType", context.getSpanType().toString());
  }

  // spotless:off
  @TableTest({
    "scenario          | name                          | value              | method       ",
    "SERVICE_NAME tag  | " + DDTags.SERVICE_NAME   + " | different service  | serviceName  ",
    "RESOURCE_NAME tag | " + DDTags.RESOURCE_NAME  + " | different resource | resourceName ",
    "SPAN_TYPE tag     | " + DDTags.SPAN_TYPE      + " | different type     | spanType     "
  })
  //spotless:on
  void specialTagsSetCertainValues(String scenario, String name, String value, String method)
      throws Exception {
    AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    context.setTag(name, value);
    span.finish();
    writer.waitForTraces(1);

    Thread thread = Thread.currentThread();
    Map<String, Object> expectedTags = new HashMap<>();
    expectedTags.put(DDTags.THREAD_NAME, thread.getName());
    expectedTags.put(DDTags.THREAD_ID, thread.getId());
    expectedTags.put(DDTags.DD_SVC_SRC, ServiceNameSources.MANUAL);
    assertTagmap(context.getTags(), expectedTags);

    Object actualValue;
    switch (method) {
      case "serviceName":
        actualValue = context.getServiceName();
        break;
      case "resourceName":
        actualValue = context.getResourceName().toString();
        break;
      case "spanType":
        actualValue = context.getSpanType().toString();
        break;
      default:
        throw new IllegalArgumentException("Unknown method: " + method);
    }
    assertEquals(value, actualValue);
  }

  static Stream<Arguments> tagsCanBeAddedToContextArguments() {
    return Stream.of(
        arguments("tag.name", "some value"),
        arguments("tag with int", 1234),
        arguments("tag-with-bool", false),
        arguments("tag_with_float", 0.321));
  }

  @ParameterizedTest
  @MethodSource("tagsCanBeAddedToContextArguments")
  void tagsCanBeAddedToContext(String name, Object value) throws Exception {
    AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    context.setTag(name, value);
    span.finish();
    writer.waitForTraces(1);

    Thread thread = Thread.currentThread();
    Map<String, Object> expectedTags = new HashMap<>();
    expectedTags.put(name, value);
    expectedTags.put(DDTags.THREAD_NAME, thread.getName());
    expectedTags.put(DDTags.THREAD_ID, thread.getId());
    expectedTags.put(DDTags.DD_SVC_SRC, ServiceNameSources.MANUAL);

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
    AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    context.setMetric("test", value);

    assertTrue(expectedType.isInstance(context.getTag("test")));

    span.finish();
  }

  @TypeConverter
  public static Number toNumber(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }
    switch (value) {
      case "Integer.MAX_VALUE":
        return Integer.MAX_VALUE;
      case "Integer.MIN_VALUE":
        return Integer.MIN_VALUE;
      case "Short.MAX_VALUE":
        return Short.MAX_VALUE;
      case "Short.MIN_VALUE":
        return Short.MIN_VALUE;
      case "Float.MAX_VALUE":
        return Float.MAX_VALUE;
      case "Float.MIN_VALUE":
        return Float.MIN_VALUE;
      case "Double.MAX_VALUE":
        return Double.MAX_VALUE;
      case "Double.MIN_VALUE":
        return Double.MIN_VALUE;
      default:
        if (value.endsWith("f")) {
          return Float.parseFloat(value);
        }
        if (value.endsWith("d")) {
          return Double.parseDouble(value);
        }
        return Integer.decode(value);
    }
  }

  @Test
  void forceKeepReallyKeepsTrace() {
    AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

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

    AgentSpan top = tracer.buildSpan("top").asChildOf((AgentSpanContext) extracted).start();
    DDSpanContext topC = (DDSpanContext) top.context();
    TraceSegment topTS = top.getRequestContext().getTraceSegment();

    AgentSpan current = tracer.buildSpan("current").asChildOf(top.context()).start();
    TraceSegment currentTS = current.getRequestContext().getTraceSegment();
    DDSpanContext currentC = (DDSpanContext) current.context();

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
    "scenario           | rate | limit     ",
    "rate=1.0 limit=10  | 1.0  | 10        ",
    "rate=0.5 limit=100 | 0.5  | 100       ",
    "rate=0.25 no limit | 0.25 | 2147483647"
  })
  void setSingleSpanSamplingTags(String scenario, double rate, int limit) {
    AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    assertEquals(UNSET, context.getSamplingPriority());

    context.setSpanSamplingPriority(rate, limit);

    assertEquals((int) SPAN_SAMPLING_RATE, context.getTag(SPAN_SAMPLING_MECHANISM_TAG));
    assertEquals(rate, context.getTag(SPAN_SAMPLING_RULE_RATE_TAG));
    assertEquals(
        limit == Integer.MAX_VALUE ? null : Double.valueOf(limit),
        context.getTag(SPAN_SAMPLING_MAX_PER_SECOND_TAG));
    assertEquals(UNSET, context.getSamplingPriority());
    assertFalse(context.getPropagationTags().createTagMap().containsKey("_dd.p.dm"));

    span.finish();
  }

  @Test
  void settingResourceNameToNullIsIgnored() {
    AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
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
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();

    verify(profilingContextIntegration, times(1)).encodeOperationName("fakeOperation");
    verify(profilingContextIntegration, times(1)).encodeResourceName("fakeResource");
    assertEquals(1, ((DDSpanContext) span.context()).getEncodedOperationName());
    assertEquals(-1, ((DDSpanContext) span.context()).getEncodedResourceName());

    when(profilingContextIntegration.encodeOperationName("newOperationName")).thenReturn(2);
    span.setOperationName("newOperationName");

    verify(profilingContextIntegration, times(1)).encodeOperationName("newOperationName");
    assertEquals(2, ((DDSpanContext) span.context()).getEncodedOperationName());

    when(profilingContextIntegration.encodeResourceName("newResourceName")).thenReturn(-2);
    span.setResourceName("newResourceName");

    verify(profilingContextIntegration, times(1)).encodeResourceName("newResourceName");
    assertEquals(-2, ((DDSpanContext) span.context()).getEncodedResourceName());

    span.finish();
  }

  @Test
  void spanIdsPrintedAsUnsignedLong() {
    AgentSpan parent =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanId(-987654321)
            .start();

    AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanId(-123456789)
            .asChildOf(parent.context())
            .start();

    DDSpanContext context = (DDSpanContext) span.context();

    assertFalse(context.toString().contains("id=-"));

    span.finish();
    parent.finish();
  }

  @Test
  void serviceNameSourceIsPropagatedFromParentToChildSpan() {
    AgentSpan parent = tracer.buildSpan("parentOperation").withServiceName("fakeService").start();

    AgentSpan child = tracer.buildSpan("childOperation").asChildOf(parent.context()).start();
    DDSpanContext childContext = (DDSpanContext) child.context();

    assertEquals(ServiceNameSources.MANUAL, childContext.getServiceNameSource());

    child.finish();
    parent.finish();
  }

  @Test
  void spanKindOrdinalConstantsAndSpanKindValuesArrayStayInSync() {
    assertEquals(DDSpanContext.SPAN_KIND_CUSTOM + 1, DDSpanContext.SPAN_KIND_VALUES.length);

    assertEquals(
        Tags.SPAN_KIND_SERVER, DDSpanContext.SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_SERVER]);
    assertEquals(
        Tags.SPAN_KIND_CLIENT, DDSpanContext.SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_CLIENT]);
    assertEquals(
        Tags.SPAN_KIND_PRODUCER, DDSpanContext.SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_PRODUCER]);
    assertEquals(
        Tags.SPAN_KIND_CONSUMER, DDSpanContext.SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_CONSUMER]);
    assertEquals(
        Tags.SPAN_KIND_INTERNAL, DDSpanContext.SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_INTERNAL]);
    assertEquals(
        Tags.SPAN_KIND_BROKER, DDSpanContext.SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_BROKER]);

    assertNull(DDSpanContext.SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_UNSET]);
    assertNull(DDSpanContext.SPAN_KIND_VALUES[DDSpanContext.SPAN_KIND_CUSTOM]);
  }

  @TableTest({
    "scenario | kindString | expectedOrdinal",
    "server   | server     | 1              ",
    "client   | client     | 2              ",
    "producer | producer   | 3              ",
    "consumer | consumer   | 4              ",
    "internal | internal   | 5              ",
    "broker   | broker     | 6              "
  })
  void setSpanKindOrdinalRoundTripsWithSpanKindValues(
      String scenario, String kindString, int expectedOrdinal) {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    DDSpanContext context = (DDSpanContext) span.context();
    context.setSpanKindOrdinal(kindString);

    assertEquals(expectedOrdinal, context.getSpanKindOrdinal());
    assertEquals(kindString, DDSpanContext.SPAN_KIND_VALUES[expectedOrdinal]);

    span.finish();
  }

  @TableTest({
    "scenario | kindString",
    "server   | server    ",
    "client   | client    ",
    "producer | producer  ",
    "consumer | consumer  ",
    "internal | internal  ",
    "broker   | broker    "
  })
  void setTagAndGetTagRoundTripForSpanKind(String scenario, String kindString) {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    span.setTag(Tags.SPAN_KIND, kindString);

    assertEquals(kindString, span.getTag(Tags.SPAN_KIND));

    span.finish();
  }

  @Test
  void getTagReturnsNullWhenSpanKindNotSet() {
    AgentSpan span = tracer.buildSpan("test", "test").start();

    assertNull(span.getTag(Tags.SPAN_KIND));

    span.finish();
  }

  @TableTest({
    "scenario | kindString",
    "server   | server    ",
    "client   | client    ",
    "producer | producer  ",
    "consumer | consumer  ",
    "internal | internal  ",
    "broker   | broker    "
  })
  void setTagThenRemoveTagClearsSpanKind(String scenario, String kindString) {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    span.setTag(Tags.SPAN_KIND, kindString);

    assertEquals(kindString, span.getTag(Tags.SPAN_KIND));

    ((DDSpan) span).context().removeTag(Tags.SPAN_KIND);

    assertNull(span.getTag(Tags.SPAN_KIND));

    span.finish();
  }

  @Test
  void setTagWithCustomSpanKindFallsBackToTagMap() {
    AgentSpan span = tracer.buildSpan("test", "test").start();
    span.setTag(Tags.SPAN_KIND, "custom-kind");

    assertEquals("custom-kind", span.getTag(Tags.SPAN_KIND));

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
    sourceWithoutCommonTags.remove(DDTags.PROFILING_ENABLED);
    sourceWithoutCommonTags.remove(DDTags.PROFILING_CONTEXT_ENGINE);
    sourceWithoutCommonTags.remove(DDTags.DSM_ENABLED);
    sourceWithoutCommonTags.remove(DDTags.DJM_ENABLED);
    if (removeThread) {
      sourceWithoutCommonTags.remove(DDTags.THREAD_ID);
      sourceWithoutCommonTags.remove(DDTags.THREAD_NAME);
    }
    assertEquals(comparison, sourceWithoutCommonTags);
  }

  private static String dataTag(String tag) {
    return "_dd." + tag + ".json";
  }
}
