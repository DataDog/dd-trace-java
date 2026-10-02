package com.datadog.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.connector.Connector;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Drives the span-enrichment branch of {@link DDEvaluator}, enabled through its settings. */
class DDEvaluatorSpanEnrichmentTest {

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

    final DDEvaluator evaluator =
        new DDEvaluator(
            mock(Runnable.class),
            Connector.NONE,
            TestSettings.of("experimental.flagging.provider.span.enrichment.enabled", "true"));
    evaluator.accept(new ServerConfiguration("", "", true, null, flags));

    final EvaluationContext ctx = new MutableContext("target").setTargetingKey("user-1");
    return evaluator.evaluate(Integer.class, "target", 23, ctx);
  }
}
