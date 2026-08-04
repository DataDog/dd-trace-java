package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.TagMap;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization of the span-build tag-ordering wart (see {@code CoreTracer} span builder).
 *
 * <p>An inbound header-derived tag ({@code coreTags}) and an explicit per-span builder tag ({@code
 * tagLedger}) that share a key are both applied to the span. Historically the builder tag is
 * applied <em>before</em> {@code coreTags}, so the header tag silently OVERRIDES the explicit
 * builder tag — flagged in-code since 2020 ("maybe the builder tags should come last").
 *
 * <p>This test pins the <b>default</b> (flag-off) behavior. The {@code
 * trace.builder.tags.precedence.enabled} flag inverts it so the explicit builder tag wins (the
 * logical precedence); that path is process-constant (a {@code static final} for DCE) and is
 * exercised by a forked test with the property set.
 */
class BuilderTagsPrecedenceTest extends DDCoreJavaSpecification {

  private static final String KEY = "test.collision.tag";
  private static final String HEADER_VALUE = "from-header";
  private static final String BUILDER_VALUE = "from-builder";

  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    tracer = tracerBuilder().build();
  }

  @AfterEach
  void cleanup() {
    tracer.close();
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
}
