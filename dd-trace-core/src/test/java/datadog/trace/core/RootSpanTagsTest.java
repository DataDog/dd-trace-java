package datadog.trace.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.api.KnownTags;
import datadog.trace.api.TagMap;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.ListWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A local root span carries the tracer's root-span tags (runtime-id, process_id, language, ...) as
 * its own tags, with or without builder-tag precedence, and they win over a same-named tag
 * extracted from the inbound request.
 */
class RootSpanTagsTest extends DDCoreJavaSpecification {
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
    return tracerBuilder()
        .withProperties(properties)
        .writer(new ListWriter())
        .localRootSpanTags(rootTags)
        .build();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aRootSpanCarriesItsRootTags(boolean builderTagsPrecedence) {
    tracer = tracer(builderTagsPrecedence);
    AgentSpan root = tracer.buildSpan("test", "root").start();
    try {
      assertEquals("jvm", root.getTag(KnownTags.LANGUAGE_NAME));
      assertEquals(42L, root.getTag(KnownTags.PROCESS_ID_NAME));
      assertEquals("value", root.getTag(CUSTOM_ROOT_TAG));
    } finally {
      root.finish();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aChildSpanGetsNoRootTags(boolean builderTagsPrecedence) {
    tracer = tracer(builderTagsPrecedence);
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rootTagsWinOverSameNamedInboundTags(boolean builderTagsPrecedence) {
    tracer = tracer(builderTagsPrecedence);
    // e.g. a request header mapped onto a root-tag name
    TagMap inbound = TagMap.create();
    inbound.set(KnownTags.PROCESS_ID_NAME, "from-request");
    inbound.set(CUSTOM_ROOT_TAG, "from-request");
    AgentSpan root =
        tracer.buildSpan("test", "root").asChildOf(new TagContext(null, inbound)).start();
    try {
      assertEquals(42L, root.getTag(KnownTags.PROCESS_ID_NAME));
      assertEquals("value", root.getTag(CUSTOM_ROOT_TAG));
    } finally {
      root.finish();
    }
  }
}
