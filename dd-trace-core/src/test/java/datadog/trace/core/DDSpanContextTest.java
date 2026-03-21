package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTags;
import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.ServiceNameSources;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DDSpanContextTest extends DDCoreSpecification {

  ListWriter writer;
  CoreTracer tracer;

  @Mock ProfilingContextIntegration profilingContextIntegration;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer =
        tracerBuilder()
            .writer(writer)
            .profilingContextIntegration(profilingContextIntegration)
            .build();
  }

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @ParameterizedTest
  @MethodSource("nullValuesForTagsArguments")
  void nullValuesForTagsDeleteExistingTags(String name, Map<String, Object> tags) throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanType("fakeType")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    context.setTag("some.tag", "asdf");
    context.setTag(name, null);
    context.setErrorFlag(true, ErrorPriorities.DEFAULT);
    span.finish();

    writer.waitForTraces(1);

    assertTagmap(context.getTags(), tags);
    assertEquals("fakeService", context.getServiceName());
    assertEquals("fakeResource", context.getResourceName().toString());
    assertEquals("fakeType", context.getSpanType());
  }

  static Stream<Arguments> nullValuesForTagsArguments() {
    Map<String, Object> commonTags = new HashMap<>();
    commonTags.put("some.tag", "asdf");
    commonTags.put(DDTags.THREAD_NAME, Thread.currentThread().getName());
    commonTags.put(DDTags.THREAD_ID, Thread.currentThread().getId());
    commonTags.put(DDTags.DD_SVC_SRC, ServiceNameSources.MANUAL);

    Map<String, Object> tagsWithoutSomeTag = new HashMap<>();
    tagsWithoutSomeTag.put(DDTags.THREAD_NAME, Thread.currentThread().getName());
    tagsWithoutSomeTag.put(DDTags.THREAD_ID, Thread.currentThread().getId());
    tagsWithoutSomeTag.put(DDTags.DD_SVC_SRC, ServiceNameSources.MANUAL);

    return Stream.of(
        Arguments.of(DDTags.SERVICE_NAME, commonTags),
        Arguments.of(DDTags.RESOURCE_NAME, commonTags),
        Arguments.of(DDTags.SPAN_TYPE, commonTags),
        Arguments.of("some.tag", tagsWithoutSomeTag));
  }

  @ParameterizedTest
  @MethodSource("specialTagsArguments")
  void specialTagsSetCertainValues(String name, Object value, String method) throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
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

    if ("serviceName".equals(method)) {
      assertEquals(value, context.getServiceName());
    } else if ("resourceName".equals(method)) {
      assertEquals(value, context.getResourceName().toString());
    } else if ("spanType".equals(method)) {
      assertEquals(value, context.getSpanType());
    }
  }

  static Stream<Arguments> specialTagsArguments() {
    return Stream.of(
        Arguments.of(DDTags.SERVICE_NAME, "different service", "serviceName"),
        Arguments.of(DDTags.RESOURCE_NAME, "different resource", "resourceName"),
        Arguments.of(DDTags.SPAN_TYPE, "different type", "spanType"));
  }

  @ParameterizedTest
  @MethodSource("tagsCanBeAddedArguments")
  void tagsCanBeAddedToTheContext(String name, Object value) throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
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

  static Stream<Arguments> tagsCanBeAddedArguments() {
    return Stream.of(
        Arguments.of("tag.name", "some value"),
        Arguments.of("tag with int", 1234),
        Arguments.of("tag-with-bool", false),
        Arguments.of("tag_with_float", 0.321));
  }

  @ParameterizedTest
  @MethodSource("metricsUseExpectedTypesArguments")
  void metricsUseTheExpectedTypes(Class<?> type, Number value) throws Exception {
    // floats should be converted to doubles.
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    context.setMetric("test", value);

    assertTrue(type.isInstance(context.getTag("test")));
  }

  static Stream<Arguments> metricsUseExpectedTypesArguments() {
    return Stream.of(
        Arguments.of(Integer.class, 0),
        Arguments.of(Integer.class, Integer.MAX_VALUE),
        Arguments.of(Integer.class, Integer.MIN_VALUE),
        Arguments.of(Short.class, Short.MAX_VALUE),
        Arguments.of(Short.class, Short.MIN_VALUE),
        Arguments.of(Float.class, Float.MAX_VALUE),
        Arguments.of(Float.class, Float.MIN_VALUE),
        Arguments.of(Double.class, Double.MAX_VALUE),
        Arguments.of(Double.class, Double.MIN_VALUE),
        Arguments.of(Float.class, 1f),
        Arguments.of(Double.class, 1d),
        Arguments.of(Float.class, 0.5f),
        Arguments.of(Double.class, 0.5d),
        Arguments.of(Integer.class, 0x55));
  }

  @Test
  void forceKeepReallyKeepsTheTrace() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    context.setSamplingPriority(SAMPLER_DROP, DEFAULT);
    assertEquals((int) SAMPLER_DROP, context.getSamplingPriority());

    // sampling priority locked
    context.lockSamplingPriority();
    // override ignored
    assertFalse(context.setSamplingPriority(USER_DROP, MANUAL));
    assertEquals((int) SAMPLER_DROP, context.getSamplingPriority());

    context.forceKeep();
    // lock is bypassed and priority set to USER_KEEP
    assertEquals((int) USER_KEEP, context.getSamplingPriority());

    span.finish();
  }

  @Test
  void setTraceSegmentTagsAndDataOnCorrectSpan() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.TagContext extracted =
        new ExtractedContext(
                DDTraceId.from(123),
                456,
                (int) datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP,
                "789",
                tracer.getPropagationTagsFactory().empty(),
                DATADOG)
            .withRequestContextDataAppSec("dummy");

    datadog.trace.bootstrap.instrumentation.api.AgentSpan top =
        tracer.buildSpan("top").asChildOf((AgentSpanContext) extracted).start();
    DDSpanContext topC = (DDSpanContext) top.context();
    datadog.trace.api.internal.TraceSegment topTS = top.getRequestContext().getTraceSegment();
    datadog.trace.bootstrap.instrumentation.api.AgentSpan current =
        tracer.buildSpan("current").asChildOf(top.context()).start();
    datadog.trace.api.internal.TraceSegment currentTS =
        current.getRequestContext().getTraceSegment();
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

  @ParameterizedTest
  @MethodSource("setSingleSpanSamplingTagsArguments")
  void setSingleSpanSamplingTags(double rate, int limit) throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();
    DDSpanContext context = (DDSpanContext) span.context();

    assertEquals((int) UNSET, context.getSamplingPriority());

    context.setSpanSamplingPriority(rate, limit);

    assertEquals((int) SPAN_SAMPLING_RATE, context.getTag(SPAN_SAMPLING_MECHANISM_TAG));
    assertEquals(rate, context.getTag(SPAN_SAMPLING_RULE_RATE_TAG));
    assertEquals(
        limit == Integer.MAX_VALUE ? null : (double) limit,
        context.getTag(SPAN_SAMPLING_MAX_PER_SECOND_TAG));
    // single span sampling should not change the trace sampling priority
    assertEquals((int) UNSET, context.getSamplingPriority());
    // make sure the `_dd.p.dm` tag has not been set by single span sampling
    assertFalse(context.getPropagationTags().createTagMap().containsKey("_dd.p.dm"));
  }

  static Stream<Arguments> setSingleSpanSamplingTagsArguments() {
    return Stream.of(
        Arguments.of(1.0, 10), Arguments.of(0.5, 100), Arguments.of(0.25, Integer.MAX_VALUE));
  }

  @Test
  void settingResourceNameToNullIsIgnored() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();

    span.setResourceName(null);

    assertEquals("fakeResource", span.getResourceName().toString());
  }

  @Test
  void settingOperationNameTriggersConstantEncoding() throws Exception {
    Mockito.when(profilingContextIntegration.encodeOperationName("fakeOperation")).thenReturn(1);
    Mockito.when(profilingContextIntegration.encodeResourceName("fakeResource")).thenReturn(-1);

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .start();

    assertEquals(1, ((DDSpanContext) span.context()).getEncodedOperationName());
    assertEquals(-1, ((DDSpanContext) span.context()).getEncodedResourceName());

    Mockito.when(profilingContextIntegration.encodeOperationName("newOperationName")).thenReturn(2);
    span.setOperationName("newOperationName");
    assertEquals(2, ((DDSpanContext) span.context()).getEncodedOperationName());

    Mockito.when(profilingContextIntegration.encodeResourceName("newResourceName")).thenReturn(-2);
    span.setResourceName("newResourceName");
    assertEquals(-2, ((DDSpanContext) span.context()).getEncodedResourceName());
  }

  @Test
  void spanIdsPrintedAsUnsignedLong() throws Exception {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan parent =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanId(-987654321)
            .start();

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span =
        tracer
            .buildSpan("fakeOperation")
            .withServiceName("fakeService")
            .withResourceName("fakeResource")
            .withSpanId(-123456789)
            .asChildOf(parent.context())
            .start();

    DDSpanContext context = (DDSpanContext) span.context();

    // even though span ID and parent ID are setup as negative numbers, they should be printed
    // as their unsigned value - asserting there is no negative sign after ids is the best we can
    // do.
    assertFalse(context.toString().contains("id=-"));
  }

  private static String dataTag(String tag) {
    return "_dd." + tag + ".json";
  }

  static void assertTagmap(Map<String, Object> source, Map<String, Object> comparison) {
    assertTagmap(source, comparison, false);
  }

  static void assertTagmap(
      Map<String, Object> source, Map<String, Object> comparison, boolean removeThread) {
    Map<String, Object> sourceWithoutCommonTags = new HashMap<>(source);
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
}
