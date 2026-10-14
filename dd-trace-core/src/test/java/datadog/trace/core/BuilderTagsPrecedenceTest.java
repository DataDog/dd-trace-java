package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Collections;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the span-build tag-ordering wart (see {@code CoreTracer} span builder).
 *
 * <p>An inbound header-derived tag ({@code coreTags}) and an explicit per-span builder tag ({@code
 * tagLedger}) that share a key are both applied to the span. Historically the builder tag is
 * applied <em>before</em> {@code coreTags}, so the header tag silently OVERRIDES the explicit
 * builder tag — flagged in-code since 2020 ("maybe the builder tags should come last").
 *
 * <p>{@link #headerTagOverridesBuilderTagByDefault} pins the <b>default</b> (flag-off) behavior.
 * {@link #builderTagWinsWhenPrecedenceEnabled} exercises the {@code
 * trace.builder.tags.precedence.enabled} flag, which inverts it so the explicit builder tag wins
 * (the logical precedence) -- read per-tracer off the {@code CoreTracerBuilder}'s own {@code
 * Config}, so no process property or forking is needed to flip it in-test.
 */
class BuilderTagsPrecedenceTest extends DDCoreJavaSpecification {

  private static final String KEY = "test.collision.tag";
  private static final String HEADER_VALUE = "from-header";
  private static final String BUILDER_VALUE = "from-builder";

  private CoreTracer tracer;

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  /** An extracted context carrying a header-derived tag ({@code coreTags}) on the given key. */
  private static ExtractedContext extractedWithHeaderTag(String key, String value) {
    return new ExtractedContext(
        DDTraceId.ONE,
        2,
        PrioritySampling.SAMPLER_KEEP,
        null,
        0,
        Collections.<String, String>emptyMap(),
        TagMap.fromMap(Collections.singletonMap(key, value)),
        null,
        PropagationTags.factory().empty(),
        null,
        DATADOG);
  }

  /**
   * Default config: the historical order applies, so the inbound header tag overrides the explicit
   * builder tag. This documents the wart; flipping the default would (intentionally) break this.
   */
  @Test
  void headerTagOverridesBuilderTagByDefault() {
    tracer = tracerBuilder().build();
    AgentSpan span =
        tracer
            .buildSpan("test", "root")
            .asChildOf(extractedWithHeaderTag(KEY, HEADER_VALUE))
            .withTag(KEY, BUILDER_VALUE)
            .start();
    try {
      Object resolved = ((DDSpan) span).getTag(KEY);
      assertEquals(
          HEADER_VALUE,
          resolved,
          "By default the historical order lets the inbound header tag override the explicit "
              + "builder tag (the documented wart). If this fails, the default ordering changed.");
    } finally {
      span.finish();
    }
  }

  /**
   * With the flag enabled, the explicit builder tag is applied last, so it wins over the inbound
   * header tag -- the inversion this PR adds.
   */
  @Test
  void builderTagWinsWhenPrecedenceEnabled() {
    Properties properties = new Properties();
    properties.setProperty(TracerConfig.TRACE_BUILDER_TAGS_PRECEDENCE_ENABLED, "true");
    tracer = tracerBuilder().withProperties(properties).build();

    AgentSpan span =
        tracer
            .buildSpan("test", "root")
            .asChildOf(extractedWithHeaderTag(KEY, HEADER_VALUE))
            .withTag(KEY, BUILDER_VALUE)
            .start();
    try {
      Object resolved = ((DDSpan) span).getTag(KEY);
      assertEquals(
          BUILDER_VALUE,
          resolved,
          "With trace.builder.tags.precedence.enabled=true, the explicit builder tag must win "
              + "over the inbound header tag.");
    } finally {
      span.finish();
    }
  }

  /**
   * Confirms the flag is read from the tracer's own config (set via {@code
   * CoreTracerBuilder#withProperties}), not a cached global default -- the bug fixed alongside this
   * test, per the review discussion on this PR.
   */
  @Test
  void precedenceFlagIsPerTracerNotAGlobalDefault() {
    tracer = tracerBuilder().build();
    Properties properties = new Properties();
    properties.setProperty(TracerConfig.TRACE_BUILDER_TAGS_PRECEDENCE_ENABLED, "true");
    CoreTracer enabledTracer = tracerBuilder().withProperties(properties).build();
    try {
      AgentSpan defaultSpan =
          tracer
              .buildSpan("test", "root")
              .asChildOf(extractedWithHeaderTag(KEY, HEADER_VALUE))
              .withTag(KEY, BUILDER_VALUE)
              .start();
      defaultSpan.finish();
      assertEquals(HEADER_VALUE, ((DDSpan) defaultSpan).getTag(KEY));

      AgentSpan enabledSpan =
          enabledTracer
              .buildSpan("test", "root")
              .asChildOf(extractedWithHeaderTag(KEY, HEADER_VALUE))
              .withTag(KEY, BUILDER_VALUE)
              .start();
      enabledSpan.finish();
      assertEquals(BUILDER_VALUE, ((DDSpan) enabledSpan).getTag(KEY));
    } finally {
      enabledTracer.close();
    }
  }
}
