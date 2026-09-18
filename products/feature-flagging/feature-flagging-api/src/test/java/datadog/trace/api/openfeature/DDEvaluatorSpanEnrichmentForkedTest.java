package datadog.trace.api.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Drives the span-enrichment branch of {@link DDEvaluator}, which the ordinary test task cannot
 * reach: the gate is read once into a static final field at class load. The property is set here
 * rather than through {@code @WithConfig} because that extension rewrites {@code Config.INSTANCE},
 * which this module does not use, and the forked task gives this class its own JVM where nothing
 * has loaded the evaluator yet.
 */
class DDEvaluatorSpanEnrichmentForkedTest {

  static {
    System.setProperty("dd.experimental.flagging.provider.span.enrichment.enabled", "true");
  }

  @Test
  void enrichmentMetadataCarriesTheSplitSerialId() {
    final ProviderEvaluation<?> result = evaluate(340132);

    assertNotNull(
        result.getFlagMetadata().getBoolean(DDEvaluator.METADATA_DO_LOG),
        "span enrichment must be on, or the assertions below pass vacuously");
    assertEquals(
        Integer.valueOf(340132),
        result.getFlagMetadata().getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID));
  }

  /**
   * Agents 1.65 and 1.66 carry Split.serialId but not the six-argument event constructor. Only the
   * exposure path may fall back on those agents; the enrichment metadata they already support must
   * keep reporting the serial id.
   */
  @Test
  void enrichmentMetadataSurvivesAnAgentWithoutTheExposureConstructor() {
    final boolean previous = DDEvaluator.USE_LEGACY_EXPOSURE_API.getAndSet(true);
    try {
      assertEquals(
          Integer.valueOf(340132),
          evaluate(340132).getFlagMetadata().getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID));
    } finally {
      DDEvaluator.USE_LEGACY_EXPOSURE_API.set(previous);
    }
  }

  @Test
  void enrichmentMetadataOmitsTheSerialIdWhenTheAgentSplitHasNoField() {
    final boolean previous = DDEvaluator.SPLIT_SERIAL_ID_SUPPORTED.getAndSet(false);
    try {
      assertNull(
          evaluate(340132).getFlagMetadata().getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID));
    } finally {
      DDEvaluator.SPLIT_SERIAL_ID_SUPPORTED.set(previous);
    }
  }

  @Test
  void enrichmentMetadataOmitsTheSerialIdWhenTheSplitHasNone() {
    assertNull(evaluate(null).getFlagMetadata().getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID));
  }

  private static ProviderEvaluation<?> evaluate(final Integer serialId) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    final Split split = new Split(emptyList(), "on", emptyMap(), serialId);
    final Allocation allocation =
        new Allocation("alloc-1", null, null, null, singletonList(split), Boolean.FALSE);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "target",
        new Flag("target", true, ValueType.INTEGER, variations, singletonList(allocation)));

    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", true, null, flags));

    final EvaluationContext ctx = new MutableContext("target").setTargetingKey("user-1");
    return evaluator.evaluate(Integer.class, "target", 23, ctx);
  }
}
