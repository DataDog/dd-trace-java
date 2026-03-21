package datadog.trace.core;

import static datadog.trace.api.DDTags.DJM_ENABLED;
import static datadog.trace.api.DDTags.DSM_ENABLED;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.ORIGIN_KEY;
import static datadog.trace.api.DDTags.PID_TAG;
import static datadog.trace.api.DDTags.PROFILING_ENABLED;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static datadog.trace.api.DDTags.SCHEMA_VERSION_TAG_KEY;
import static datadog.trace.api.DDTags.THREAD_ID;
import static datadog.trace.api.DDTags.THREAD_NAME;
import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

class CoreSpanBuilderTest extends DDCoreSpecification {

  ListWriter writer;
  CoreTracer tracer;

  @BeforeEach
  void setUp() throws Exception {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void buildSimpleSpan() {
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").withServiceName("foo").start();
    assertEquals("op name", span.getOperationName());
  }

  @Test
  void buildComplexSpan() {
    String expectedName = "fakeName";
    Map<String, Object> tags = new HashMap<>();
    tags.put("1", true);
    tags.put("2", "fakeString");
    tags.put("3", 42.0);

    CoreTracer.CoreSpanBuilder builder =
        tracer.buildSpan("test", expectedName).withServiceName("foo");
    for (Map.Entry<String, Object> entry : tags.entrySet()) {
      if (entry.getValue() instanceof Boolean) {
        builder = builder.withTag(entry.getKey(), (Boolean) entry.getValue());
      } else if (entry.getValue() instanceof Number) {
        builder = builder.withTag(entry.getKey(), (Number) entry.getValue());
      } else {
        builder = builder.withTag(entry.getKey(), (String) entry.getValue());
      }
    }

    DDSpan span = (DDSpan) builder.start();
    assertEquals(expectedName, span.getOperationName());
    for (Map.Entry<String, Object> entry : tags.entrySet()) {
      assertEquals(entry.getValue(), span.getTags().get(entry.getKey()));
    }

    span = (DDSpan) tracer.buildSpan("test", expectedName).withServiceName("foo").start();
    Map<String, Object> expectedTags = new HashMap<>();
    expectedTags.put(THREAD_NAME, Thread.currentThread().getName());
    expectedTags.put(THREAD_ID, Thread.currentThread().getId());
    expectedTags.put(RUNTIME_ID_TAG, Config.get().getRuntimeId());
    expectedTags.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    expectedTags.put(PID_TAG, Config.get().getProcessId());
    expectedTags.put(SCHEMA_VERSION_TAG_KEY, SpanNaming.instance().version());
    expectedTags.putAll(productTags());
    assertEquals(expectedTags, span.getTags());

    String expectedResource = "fakeResource";
    String expectedService = "fakeService";
    String expectedType = "fakeType";

    span =
        (DDSpan)
            tracer
                .buildSpan("test", expectedName)
                .withServiceName("foo")
                .withResourceName(expectedResource)
                .withServiceName(expectedService)
                .withErrorFlag()
                .withSpanType(expectedType)
                .start();

    DDSpanContext context = span.context();

    assertEquals(expectedResource, context.getResourceName());
    assertTrue(context.getErrorFlag());
    assertEquals(expectedService, context.getServiceName());
    assertEquals(expectedType, context.getSpanType());
    assertEquals(Thread.currentThread().getName(), context.getTag(THREAD_NAME));
    assertEquals(Thread.currentThread().getId(), context.getTag(THREAD_ID));
  }

  @TableTest({
    "scenario  | name      | value",
    "null tag  | null.tag  | null ",
    "empty tag | empty.tag | empty"
  })
  @ParameterizedTest(name = "[{index}] setting {1} should remove - {0}")
  void settingTagShouldRemove(String name, String value) throws Exception {
    String strValue = "null".equals(value) ? null : "";
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("test", "op name")
                .withTag(name, "tag value")
                .withTag(name, strValue)
                .start();

    assertNull(span.getTags().get(name));

    span.setTag(name, "a tag");
    assertEquals("a tag", span.getTags().get(name));

    span.setTag(name, strValue);
    assertNull(span.getTags().get(name));
  }

  @Test
  void shouldBuildSpanTimestampInNano() {
    long expectedTimestamp = 487517802L * 1000 * 1000L;
    String expectedName = "fakeName";

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("test", expectedName)
                .withServiceName("foo")
                .withStartTimestamp(expectedTimestamp)
                .start();

    assertEquals(expectedTimestamp * 1000L, span.getStartTime());

    long start = System.currentTimeMillis();
    span = (DDSpan) tracer.buildSpan("test", expectedName).withServiceName("foo").start();
    long stop = System.currentTimeMillis();

    assertTrue(span.getStartTime() >= MILLISECONDS.toNanos(start - 1));
    assertTrue(span.getStartTime() <= MILLISECONDS.toNanos(stop + 1));
  }

  @Test
  void shouldLinkToParentSpan() {
    long spanId = 1;
    DDTraceId traceId = DDTraceId.ONE;
    long expectedParentId = spanId;

    DDSpanContext mockedContext = mock(DDSpanContext.class);
    when(mockedContext.getTraceId()).thenReturn(traceId);
    when(mockedContext.getSpanId()).thenReturn(spanId);
    when(mockedContext.getServiceName()).thenReturn("foo");
    when(mockedContext.getBaggageItems())
        .thenReturn(java.util.Collections.<String, String>emptyMap());
    when(mockedContext.getTraceCollector()).thenReturn(tracer.createTraceCollector(DDTraceId.ONE));
    when(mockedContext.getPathwayContext()).thenReturn(NoopPathwayContext.INSTANCE);

    String expectedName = "fakeName";

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("test", expectedName)
                .withServiceName("foo")
                .asChildOf(mockedContext)
                .start();

    DDSpanContext actualContext = span.context();

    assertEquals(expectedParentId, actualContext.getParentId());
    assertEquals(traceId, actualContext.getTraceId());
  }

  static Stream<Arguments> shouldLinkToParentSpanImplicitlyArgs() {
    return Stream.of(
        Arguments.of(false, "service", false),
        Arguments.of(true, "service", true),
        Arguments.of(false, "another service", true),
        Arguments.of(true, "another service", true));
  }

  @ParameterizedTest
  @MethodSource("shouldLinkToParentSpanImplicitlyArgs")
  void shouldLinkToParentSpanImplicitly(
      boolean noopParent, String serviceName, boolean expectTopLevel) throws Exception {
    AgentScope parent =
        tracer.activateSpan(
            noopParent
                ? noopSpan()
                : tracer.buildSpan("test", "parent").withServiceName("service").start());

    long expectedParentId = noopParent ? DDSpanId.ZERO : parent.span().context().getSpanId();

    String expectedName = "fakeName";

    DDSpan span =
        (DDSpan) tracer.buildSpan("test", expectedName).withServiceName(serviceName).start();

    DDSpanContext actualContext = span.context();

    assertEquals(expectedParentId, actualContext.getParentId());
    assertEquals(expectTopLevel, span.isTopLevel());

    parent.close();
  }

  @Test
  void shouldInheritTheDDParentAttributes() {
    String expectedName = "fakeName";
    String expectedParentResourceName = "fakeResourceName";
    String expectedParentType = "fakeType";
    String expectedParentServiceName = "fakeServiceName";
    String expectedChildServiceName = "fakeServiceName-child";
    String expectedChildResourceName = "fakeResourceName-child";
    String expectedChildType = "fakeType-child";
    String expectedBaggageItemKey = "fakeKey";
    String expectedBaggageItemValue = "fakeValue";

    DDSpan parent =
        (DDSpan)
            tracer
                .buildSpan("test", expectedName)
                .withServiceName("foo")
                .withResourceName(expectedParentResourceName)
                .withSpanType(expectedParentType)
                .start();

    parent.setBaggageItem(expectedBaggageItemKey, expectedBaggageItemValue);

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("test", expectedName)
                .withServiceName(expectedParentServiceName)
                .asChildOf(parent)
                .start();

    assertEquals(expectedName, span.getOperationName());
    assertEquals(expectedBaggageItemValue, span.getBaggageItem(expectedBaggageItemKey));
    assertEquals(expectedParentServiceName, span.context().getServiceName());
    assertEquals(expectedName, span.context().getResourceName());
    assertNull(span.context().getSpanType());
    assertTrue(span.isTopLevel());

    span =
        (DDSpan)
            tracer
                .buildSpan("test", expectedName)
                .withServiceName(expectedChildServiceName)
                .withResourceName(expectedChildResourceName)
                .withSpanType(expectedChildType)
                .asChildOf(parent)
                .start();

    assertEquals(expectedName, span.getOperationName());
    assertEquals(expectedBaggageItemValue, span.getBaggageItem(expectedBaggageItemKey));
    assertEquals(expectedChildServiceName, span.context().getServiceName());
    assertEquals(expectedChildResourceName, span.context().getResourceName());
    assertEquals(expectedChildType, span.context().getSpanType());
  }

  @Test
  void shouldTrackAllSpansInTrace() throws Exception {
    int nbSamples = 10;

    DDSpan root = (DDSpan) tracer.buildSpan("test", "fake_O").withServiceName("foo").start();

    DDSpan lastSpan = root;
    java.util.List<DDSpan> spans = new java.util.ArrayList<>();

    for (int i = 1; i <= nbSamples; i++) {
      lastSpan =
          (DDSpan)
              tracer
                  .buildSpan("test", "fake_" + i)
                  .withServiceName("foo")
                  .asChildOf(lastSpan)
                  .start();
      spans.add(lastSpan);
      lastSpan.finish();
    }

    PendingTrace pendingTrace = (PendingTrace) root.context().getTraceCollector();
    assertEquals(root, pendingTrace.getRootSpan());
    assertEquals(nbSamples, pendingTrace.size());
    java.lang.reflect.Field spansField = PendingTrace.class.getDeclaredField("spans");
    spansField.setAccessible(true);
    @SuppressWarnings("unchecked")
    java.util.Collection<DDSpan> pendingSpans =
        (java.util.Collection<DDSpan>) spansField.get(pendingTrace);
    assertTrue(pendingSpans.containsAll(spans));
  }

  static Stream<Arguments> extractedContextShouldPopulateNewSpanDetailsArgs() {
    return Stream.of(
        Arguments.of(
            new ExtractedContext(
                DDTraceId.ONE,
                2L,
                PrioritySampling.SAMPLER_DROP,
                null,
                0L,
                new HashMap<String, String>(),
                new HashMap<String, Object>(),
                null,
                PropagationTags.factory()
                    .fromHeaderValue(
                        PropagationTags.HeaderType.DATADOG,
                        "_dd.p.dm=934086a686-4,_dd.p.anytag=value"),
                null,
                DATADOG)),
        Arguments.of(
            new ExtractedContext(
                DDTraceId.from(3),
                4L,
                (int) PrioritySampling.SAMPLER_KEEP,
                "some-origin",
                0L,
                java.util.Collections.singletonMap("asdf", "qwer"),
                singletonMap2(ORIGIN_KEY, "some-origin", "zxcv", "1234"),
                null,
                PropagationTags.factory().empty(),
                null,
                DATADOG)));
  }

  @ParameterizedTest
  @MethodSource("extractedContextShouldPopulateNewSpanDetailsArgs")
  void extractedContextShouldPopulateNewSpanDetails(ExtractedContext extractedContext) {
    Thread thread = Thread.currentThread();
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").asChildOf(extractedContext).start();

    assertEquals(extractedContext.getTraceId(), span.getTraceId());
    assertEquals(extractedContext.getSpanId(), span.getParentId());
    assertEquals(extractedContext.getSamplingPriority(), span.getSamplingPriority());
    assertEquals(extractedContext.getOrigin(), span.context().getOrigin());
    assertEquals(extractedContext.getBaggage(), span.context().getBaggageItems());

    assertEquals(thread.getId(), span.getTag(THREAD_ID));
    assertEquals(thread.getName(), span.getTag(THREAD_NAME));
    assertEquals(
        extractedContext.getPropagationTags().headerValue(PropagationTags.HeaderType.DATADOG),
        span.context().getPropagationTags().headerValue(PropagationTags.HeaderType.DATADOG));
  }

  @Test
  void buildContextFromExtractedContextWithRestart() throws Exception {
    injectSysConfig("trace.propagation.behavior.extract", "restart");
    ExtractedContext extractedContext =
        new ExtractedContext(
            DDTraceId.ONE,
            2,
            PrioritySampling.SAMPLER_DROP,
            null,
            0,
            new HashMap<>(),
            new HashMap<>(),
            null,
            PropagationTags.factory()
                .fromHeaderValue(
                    PropagationTags.HeaderType.DATADOG, "_dd.p.dm=934086a686-4,_dd.p.anytag=value"),
            null,
            DATADOG);
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").asChildOf(extractedContext).start();

    assertNotEquals(extractedContext.getTraceId(), span.getTraceId());
    assertNotEquals(extractedContext.getSpanId(), span.getParentId());
    assertEquals((int) PrioritySampling.UNSET, (int) span.samplingPriority());

    List<AgentSpanLink> spanLinks = span.links;
    assertEquals(1, spanLinks.size());
    AgentSpanLink link = spanLinks.get(0);
    assertEquals(extractedContext.getTraceId(), link.traceId());
    assertEquals(extractedContext.getSpanId(), link.spanId());
  }

  @Test
  void buildContextFromExtractedContextWithIgnore() throws Exception {
    injectSysConfig("trace.propagation.behavior.extract", "ignore");
    ExtractedContext extractedContext =
        new ExtractedContext(
            DDTraceId.ONE,
            2,
            PrioritySampling.SAMPLER_DROP,
            null,
            0,
            new HashMap<>(),
            new HashMap<>(),
            null,
            PropagationTags.factory()
                .fromHeaderValue(
                    PropagationTags.HeaderType.DATADOG, "_dd.p.dm=934086a686-4,_dd.p.anytag=value"),
            null,
            DATADOG);
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").asChildOf(extractedContext).start();

    assertNotEquals(extractedContext.getTraceId(), span.getTraceId());
    assertNotEquals(extractedContext.getSpanId(), span.getParentId());
    assertEquals((int) PrioritySampling.UNSET, (int) span.samplingPriority());
    assertTrue(span.links.isEmpty());
  }

  static Stream<Arguments> tagContextShouldPopulateDefaultSpanDetailsArgs() {
    return Stream.of(
        Arguments.of(new TagContext(null, TagMap.fromMap(new HashMap<>()))),
        Arguments.of(new TagContext("some-origin", TagMap.fromMap(singletonMap("asdf", "qwer")))));
  }

  @ParameterizedTest
  @MethodSource("tagContextShouldPopulateDefaultSpanDetailsArgs")
  void tagContextShouldPopulateDefaultSpanDetails(TagContext tagContext) {
    Thread thread = Thread.currentThread();
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").asChildOf(tagContext).start();

    assertNotEquals(DDTraceId.ZERO, span.getTraceId());
    assertEquals(DDSpanId.ZERO, span.getParentId());
    assertNull(span.getSamplingPriority());
    assertEquals(tagContext.getOrigin(), span.context().getOrigin());
    assertTrue(span.context().getBaggageItems().isEmpty());

    Map<String, Object> expectedContextTags = new HashMap<>(tagContext.getTags());
    expectedContextTags.put(RUNTIME_ID_TAG, Config.get().getRuntimeId());
    expectedContextTags.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    expectedContextTags.put(THREAD_NAME, thread.getName());
    expectedContextTags.put(THREAD_ID, thread.getId());
    expectedContextTags.put(PID_TAG, Config.get().getProcessId());
    expectedContextTags.put(SCHEMA_VERSION_TAG_KEY, SpanNaming.instance().version());
    expectedContextTags.putAll(productTags());
    assertEquals(expectedContextTags, span.context().getTags());
  }

  static Stream<Arguments> globalSpanTagsPopulatedOnEachSpanArgs() {
    return Stream.of(
        Arguments.of("", new HashMap<>()),
        Arguments.of("is:val:id", singletonMap("is", "val:id")),
        Arguments.of("a:x", singletonMap("a", "x")),
        Arguments.of("a:a,a:b,a:c", singletonMap("a", "c")),
        Arguments.of("a:1,b-c:d", singletonMap2("a", "1", "b-c", "d")));
  }

  @ParameterizedTest
  @MethodSource("globalSpanTagsPopulatedOnEachSpanArgs")
  void globalSpanTagsPopulatedOnEachSpan(String tagString, Map<String, Object> tags)
      throws Exception {
    injectSysConfig("dd.trace.span.tags", tagString);
    CoreTracer customTracer = tracerBuilder().writer(writer).build();
    DDSpan span = (DDSpan) customTracer.buildSpan("test", "op name").withServiceName("foo").start();

    Map<String, Object> expectedTags = new HashMap<>(tags);
    expectedTags.put(THREAD_NAME, Thread.currentThread().getName());
    expectedTags.put(THREAD_ID, Thread.currentThread().getId());
    expectedTags.put(RUNTIME_ID_TAG, Config.get().getRuntimeId());
    expectedTags.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    expectedTags.put(PID_TAG, Config.get().getProcessId());
    expectedTags.put(SCHEMA_VERSION_TAG_KEY, SpanNaming.instance().version());
    expectedTags.putAll(productTags());
    assertEquals(expectedTags, span.getTags());

    customTracer.close();
  }

  @Test
  void canOverwriteRequestContextDataWithBuilderFromEmpty() {
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span1 = tracer.startSpan("test", "span1");

    assertNull(span1.getRequestContext().getData(RequestContextSlot.APPSEC));
    assertNull(span1.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY));
    assertNull(span1.getRequestContext().getData(RequestContextSlot.IAST));

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span2 =
        tracer
            .buildSpan("test", "span2")
            .asChildOf(span1.context())
            .withRequestContextData(RequestContextSlot.APPSEC, "override")
            .withRequestContextData(RequestContextSlot.CI_VISIBILITY, "override")
            .withRequestContextData(RequestContextSlot.IAST, "override")
            .start();

    assertEquals("override", span2.getRequestContext().getData(RequestContextSlot.APPSEC));
    assertEquals("override", span2.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY));
    assertEquals("override", span2.getRequestContext().getData(RequestContextSlot.IAST));

    span2.finish();
    span1.finish();
  }

  @Test
  void canOverwriteRequestContextDataWithBuilder() {
    TagContext context =
        new TagContext()
            .withCiVisibilityContextData("value")
            .withRequestContextDataIast("value")
            .withRequestContextDataAppSec("value");
    datadog.trace.bootstrap.instrumentation.api.AgentSpan span1 =
        tracer.buildSpan("test", "span1").asChildOf(context).start();

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span2 =
        tracer.buildSpan("test", "span2").asChildOf(span1.context()).start();

    assertEquals("value", span2.getRequestContext().getData(RequestContextSlot.APPSEC));
    assertEquals("value", span2.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY));
    assertEquals("value", span2.getRequestContext().getData(RequestContextSlot.IAST));

    datadog.trace.bootstrap.instrumentation.api.AgentSpan span3 =
        tracer
            .buildSpan("test", "span3")
            .asChildOf(span2.context())
            .withRequestContextData(RequestContextSlot.APPSEC, "override")
            .withRequestContextData(RequestContextSlot.CI_VISIBILITY, "override")
            .withRequestContextData(RequestContextSlot.IAST, "override")
            .start();

    assertEquals("override", span3.getRequestContext().getData(RequestContextSlot.APPSEC));
    assertEquals("override", span3.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY));
    assertEquals("override", span3.getRequestContext().getData(RequestContextSlot.IAST));

    span3.finish();
    span2.finish();
    span1.finish();
  }

  Map<String, Object> productTags() {
    Map<String, Object> productTags = new HashMap<>();
    productTags.put(PROFILING_ENABLED, Config.get().isProfilingEnabled() ? 1 : 0);
    if (Config.get().isDataStreamsEnabled()) {
      productTags.put(DSM_ENABLED, 1);
    }
    if (Config.get().isDataJobsEnabled()) {
      productTags.put(DJM_ENABLED, 1);
    }
    return productTags;
  }

  static Map<String, Object> singletonMap(String key, Object value) {
    Map<String, Object> m = new HashMap<>();
    m.put(key, value);
    return m;
  }

  static Map<String, Object> singletonMap2(String k1, Object v1, String k2, Object v2) {
    Map<String, Object> m = new HashMap<>();
    m.put(k1, v1);
    m.put(k2, v2);
    return m;
  }
}
