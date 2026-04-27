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
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.junit.utils.config.WithConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

public class CoreSpanBuilderTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
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

    AgentTracer.SpanBuilder builder = tracer.buildSpan(expectedName).withServiceName("foo");
    for (Map.Entry<String, Object> entry : tags.entrySet()) {
      builder = builder.withTag(entry.getKey(), entry.getValue());
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
    "null tag  | null.tag  |      ",
    "empty tag | empty.tag | ''   "
  })
  void settingNameShouldRemove(String name, String value) {
    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("test", "op name")
                .withTag(name, "tag value")
                .withTag(name, value)
                .start();

    assertNull(span.getTags().get(name));

    span.setTag(name, "a tag");
    assertEquals("a tag", span.getTags().get(name));

    span.setTag(name, value);
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
    long spanId = 1L;
    DDTraceId traceId = DDTraceId.ONE;
    long expectedParentId = spanId;

    DDSpanContext mockedContext = mock(DDSpanContext.class);
    when(mockedContext.getTraceId()).thenReturn(traceId);
    when(mockedContext.getSpanId()).thenReturn(spanId);
    when(mockedContext.getServiceName()).thenReturn("foo");
    when(mockedContext.getBaggageItems()).thenReturn(Collections.<String, String>emptyMap());
    when(mockedContext.getTraceCollector()).thenReturn(tracer.createTraceCollector(DDTraceId.ONE));
    when(mockedContext.getPathwayContext()).thenReturn(NoopPathwayContext.INSTANCE);

    DDSpan span =
        (DDSpan)
            tracer
                .buildSpan("test", "fakeName")
                .withServiceName("foo")
                .asChildOf(mockedContext)
                .start();

    DDSpanContext actualContext = span.context();
    assertEquals(expectedParentId, actualContext.getParentId());
    assertEquals(traceId, actualContext.getTraceId());
  }

  @TableTest({
    "scenario                  | noopParent | serviceName     | expectTopLevel",
    "same service, no noop     | false      | service         | false         ",
    "noop parent, same service | true       | service         | true          ",
    "diff service, no noop     | false      | another service | true          ",
    "noop parent, diff service | true       | another service | true          "
  })
  void shouldLinkToParentSpanImplicitly(
      boolean noopParent, String serviceName, boolean expectTopLevel) {
    try (AgentScope parent =
        tracer.activateSpan(
            noopParent
                ? noopSpan()
                : tracer.buildSpan("test", "parent").withServiceName("service").start())) {
      long expectedParentId = noopParent ? DDSpanId.ZERO : parent.span().context().getSpanId();

      DDSpan span =
          (DDSpan) tracer.buildSpan("test", "fakeName").withServiceName(serviceName).start();

      DDSpanContext actualContext = span.context();
      assertEquals(expectedParentId, actualContext.getParentId());
      assertEquals(expectTopLevel, span.isTopLevel());
    }
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

    // ServiceName and SpanType are always set by the parent if they are not present in the child
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
    assertTrue(span.isTopLevel()); // service names differ between parent and child

    // ServiceName and SpanType are always overwritten by the child if they are present
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
  void shouldTrackAllSpansInTrace() {
    int nbSamples = 10;
    List<DDSpan> spans = new ArrayList<>();

    DDSpan root = (DDSpan) tracer.buildSpan("test", "fake_O").withServiceName("foo").start();
    DDSpan lastSpan = root;

    for (int i = 1; i <= 10; i++) {
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

    PendingTrace traceCollector = (PendingTrace) root.context().getTraceCollector();
    assertEquals(root, traceCollector.getRootSpan());
    assertEquals(nbSamples, traceCollector.size());
    assertTrue(traceCollector.getSpans().containsAll(spans));
    DDSpan randomSpan = spans.get((int) (Math.random() * nbSamples));
    assertTrue(
        ((PendingTrace) randomSpan.context().getTraceCollector()).getSpans().containsAll(spans));
  }

  static Stream<ExtractedContext> extractedContextShouldPopulateNewSpanDetailsArguments() {
    return Stream.of(
        new ExtractedContext(
            DDTraceId.ONE,
            2,
            PrioritySampling.SAMPLER_DROP,
            null,
            0,
            Collections.<String, String>emptyMap(),
            Collections.<String, Object>emptyMap(),
            null,
            PropagationTags.factory()
                .fromHeaderValue(
                    PropagationTags.HeaderType.DATADOG, "_dd.p.dm=934086a686-4,_dd.p.anytag=value"),
            null,
            DATADOG),
        new ExtractedContext(
            DDTraceId.from(3),
            4,
            PrioritySampling.SAMPLER_KEEP,
            "some-origin",
            0,
            Collections.singletonMap("asdf", "qwer"),
            buildTagsMap(ORIGIN_KEY, "some-origin", "zxcv", "1234"),
            null,
            PropagationTags.factory().empty(),
            null,
            DATADOG));
  }

  @ParameterizedTest
  @MethodSource("extractedContextShouldPopulateNewSpanDetailsArguments")
  void extractedContextShouldPopulateNewSpanDetails(ExtractedContext extractedContext) {
    Thread thread = Thread.currentThread();
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").asChildOf(extractedContext).start();

    assertEquals(extractedContext.getTraceId(), span.getTraceId());
    assertEquals(extractedContext.getSpanId(), span.getParentId());
    assertEquals(extractedContext.getSamplingPriority(), (int) span.getSamplingPriority());
    assertEquals(extractedContext.getOrigin(), span.context().getOrigin());
    assertEquals(extractedContext.getBaggage(), span.context().getBaggageItems());
    assertEquals(thread.getId(), span.getTag(THREAD_ID));
    assertEquals(thread.getName(), span.getTag(THREAD_NAME));
    assertEquals(
        extractedContext.getPropagationTags().headerValue(PropagationTags.HeaderType.DATADOG),
        span.context().getPropagationTags().headerValue(PropagationTags.HeaderType.DATADOG));
  }

  @Test
  @WithConfig(key = "trace.propagation.behavior.extract", value = "restart")
  void buildContextFromExtractedContextWithRestartBehavior() {
    ExtractedContext extractedContext =
        new ExtractedContext(
            DDTraceId.ONE,
            2,
            PrioritySampling.SAMPLER_DROP,
            null,
            0,
            Collections.<String, String>emptyMap(),
            Collections.<String, Object>emptyMap(),
            null,
            PropagationTags.factory()
                .fromHeaderValue(
                    PropagationTags.HeaderType.DATADOG, "_dd.p.dm=934086a686-4,_dd.p.anytag=value"),
            null,
            DATADOG);
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").asChildOf(extractedContext).start();

    assertNotEquals(extractedContext.getTraceId(), span.getTraceId());
    assertNotEquals(extractedContext.getSpanId(), span.getParentId());
    assertEquals(PrioritySampling.UNSET, span.samplingPriority());

    List<? extends AgentSpanLink> spanLinks = span.getLinks();
    assertEquals(1, spanLinks.size());
    AgentSpanLink link = spanLinks.get(0);
    assertEquals(extractedContext.getTraceId(), link.traceId());
    assertEquals(extractedContext.getSpanId(), link.spanId());
    assertEquals(
        extractedContext.getPropagationTags().headerValue(PropagationTags.HeaderType.W3C),
        link.traceState());
  }

  @Test
  @WithConfig(key = "trace.propagation.behavior.extract", value = "ignore")
  void buildContextFromExtractedContextWithIgnoreBehavior() {
    ExtractedContext extractedContext =
        new ExtractedContext(
            DDTraceId.ONE,
            2,
            PrioritySampling.SAMPLER_DROP,
            null,
            0,
            Collections.<String, String>emptyMap(),
            Collections.<String, Object>emptyMap(),
            null,
            PropagationTags.factory()
                .fromHeaderValue(
                    PropagationTags.HeaderType.DATADOG, "_dd.p.dm=934086a686-4,_dd.p.anytag=value"),
            null,
            DATADOG);
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").asChildOf(extractedContext).start();

    assertNotEquals(extractedContext.getTraceId(), span.getTraceId());
    assertNotEquals(extractedContext.getSpanId(), span.getParentId());
    assertEquals(PrioritySampling.UNSET, span.samplingPriority());
    assertTrue(span.getLinks().isEmpty());
  }

  @TableTest({
    "scenario      | origin      | tagMap      ",
    "empty tag map |             | [:]         ",
    "some origin   | some-origin | [asdf: qwer]"
  })
  void tagContextShouldPopulateDefaultSpanDetails(
      String scenario, String origin, Map<String, String> tagMap) {
    Thread thread = Thread.currentThread();
    TagContext tagContext = new TagContext(origin, TagMap.fromMap(tagMap));
    DDSpan span = (DDSpan) tracer.buildSpan("test", "op name").asChildOf(tagContext).start();

    assertNotEquals(DDTraceId.ZERO, span.getTraceId());
    assertEquals(DDSpanId.ZERO, span.getParentId());
    assertNull(span.getSamplingPriority());
    assertEquals(tagContext.getOrigin(), span.context().getOrigin());
    assertEquals(Collections.emptyMap(), span.context().getBaggageItems());

    Map<String, Object> expectedTags = new HashMap<>();
    if (tagContext.getTags() != null) {
      expectedTags.putAll(tagContext.getTags());
    }
    expectedTags.put(RUNTIME_ID_TAG, Config.get().getRuntimeId());
    expectedTags.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    expectedTags.put(THREAD_NAME, thread.getName());
    expectedTags.put(THREAD_ID, thread.getId());
    expectedTags.put(PID_TAG, Config.get().getProcessId());
    expectedTags.put(SCHEMA_VERSION_TAG_KEY, SpanNaming.instance().version());
    expectedTags.putAll(productTags());
    assertEquals(expectedTags, span.context().getTags());
  }

  static Stream<Arguments> globalSpanTagsPopulatedOnEachSpanArguments() {
    return Stream.of(
        arguments("", Collections.emptyMap()),
        arguments("is:val:id", Collections.singletonMap("is", "val:id")),
        arguments("a:x", Collections.singletonMap("a", "x")),
        arguments("a:a,a:b,a:c", Collections.singletonMap("a", "c")),
        arguments("a:1,b-c:d", buildStringMap("a", "1", "b-c", "d")));
  }

  @TableTest({
    "scenario          | tagString   | tags          ",
    "empty             | ''          | [:]           ",
    "column            | is:val:id   | [is: 'val:id']",
    "single            | a:x         | [a: x]        ",
    "same multi-values | a:a,a:b,a:c | [a: c]        ",
    "multi values      | a:1,b-c:d   | [a: 1, b-c: d]"
  })
  void globalSpanTagsPopulatedOnEachSpan(String tagString, Map<String, String> tags) {
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
  }

  @Test
  void canOverwriteRequestContextDataWithBuilderFromEmpty() {
    AgentSpan span1 = tracer.startSpan("test", "span1");

    assertNull(span1.getRequestContext().getData(RequestContextSlot.APPSEC));
    assertNull(span1.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY));
    assertNull(span1.getRequestContext().getData(RequestContextSlot.IAST));

    AgentSpan span2 =
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
    AgentSpan span1 = tracer.buildSpan("test", "span1").asChildOf(context).start();

    AgentSpan span2 = tracer.buildSpan("test", "span2").asChildOf(span1.context()).start();

    assertEquals("value", span2.getRequestContext().getData(RequestContextSlot.APPSEC));
    assertEquals("value", span2.getRequestContext().getData(RequestContextSlot.CI_VISIBILITY));
    assertEquals("value", span2.getRequestContext().getData(RequestContextSlot.IAST));

    AgentSpan span3 =
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

  private Map<String, Object> productTags() {
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

  private static Map<String, Object> buildTagsMap(String... keyValues) {
    Map<String, Object> map = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  private static Map<String, String> buildStringMap(String... keyValues) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }
}
