package datadog.trace.core;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.DynamicConfig;
import datadog.trace.api.KnownTags;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * With builder tags taking precedence, a root span reads its root-span tags through the shared
 * trace-level parent instead of holding copies: the same values, none of them stored locally, so
 * the span's own store keeps only span-level tags (the slot coloring lets a span-level and a
 * trace-level tag share a slot bit).
 */
class RootSpanTagsReadThroughTest extends DDCoreJavaSpecification {
  private static final String CUSTOM_ROOT_TAG = "only_root";

  private CoreTracer tracer;

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  private CoreTracer tracer(boolean builderTagsPrecedence) {
    Map<String, Object> rootTags = new LinkedHashMap<>();
    rootTags.put(KnownTags.LANGUAGE_NAME, "jvm");
    rootTags.put(KnownTags.PROCESS_ID_NAME, 42L);
    rootTags.put(CUSTOM_ROOT_TAG, "value");
    Properties properties = new Properties();
    properties.setProperty(
        TracerConfig.TRACE_BUILDER_TAGS_PRECEDENCE_ENABLED, String.valueOf(builderTagsPrecedence));
    return tracerBuilder().withProperties(properties).localRootSpanTags(rootTags).build();
  }

  @Test
  void aRootSpanReadsItsRootTagsThroughTheTraceLevelParent() throws Exception {
    tracer = tracer(false);
    int copiedLocally = localKnownTagCount(rootSpanOf(tracer));
    tracer.close();

    tracer = tracer(true);
    DDSpan root = rootSpanOf(tracer);
    assertEquals("jvm", root.getTag(KnownTags.LANGUAGE_NAME));
    assertEquals(42L, root.getTag(KnownTags.PROCESS_ID_NAME));
    assertEquals("value", root.getTag(CUSTOM_ROOT_TAG));
    assertEquals(copiedLocally - 2, localKnownTagCount(root));
  }

  @Test
  void aChildSpanStillGetsNoRootTags() {
    tracer = tracer(true);
    AgentSpan root = tracer.buildSpan("test", "root").start();
    AgentSpan child = tracer.buildSpan("test", "child").asChildOf(root.spanContext()).start();
    try {
      assertNull(child.getTag(KnownTags.LANGUAGE_NAME));
      assertNull(child.getTag(CUSTOM_ROOT_TAG));
    } finally {
      child.finish();
      root.finish();
    }
  }

  @Test
  void rootSpansShareTheTraceLevelParentUntilTheTracerTagsChange() throws Exception {
    tracer = tracer(true);
    DDSpan first = rootSpanOf(tracer);
    DDSpan second = rootSpanOf(tracer); // same config snapshot: the cached parent
    assertEquals("value", first.getTag(CUSTOM_ROOT_TAG));
    assertEquals("value", second.getTag(CUSTOM_ROOT_TAG));

    // a remote-config tracing_tags change gives new merged tracer tags, so the parent is rebuilt
    Field dynamicConfigField = CoreTracer.class.getDeclaredField("dynamicConfig");
    dynamicConfigField.setAccessible(true);
    DynamicConfig<?> dynamicConfig = (DynamicConfig<?>) dynamicConfigField.get(tracer);
    dynamicConfig.current().setTracingTags(singletonMap("rc.tag", "remote")).apply();

    DDSpan afterChange = rootSpanOf(tracer);
    assertEquals("remote", afterChange.getTag("rc.tag"));
    assertEquals("value", afterChange.getTag(CUSTOM_ROOT_TAG));
  }

  private static DDSpan rootSpanOf(CoreTracer tracer) {
    AgentSpan span = tracer.buildSpan("test", "root").start();
    span.finish();
    return (DDSpan) span;
  }

  /** The known tags the span's own id store holds, not counting what it reads through. */
  private static int localKnownTagCount(DDSpan span) throws Exception {
    Field knownTags = DDSpanContext.class.getDeclaredField("knownTags");
    knownTags.setAccessible(true);
    Object ids = knownTags.get(span.spanContext());
    Method computeSize =
        Class.forName("datadog.trace.api.IdStore").getDeclaredMethod("computeSize");
    computeSize.setAccessible(true);
    return (Integer) computeSize.invoke(ids);
  }
}
