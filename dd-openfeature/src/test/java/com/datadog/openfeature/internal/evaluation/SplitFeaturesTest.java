package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURES_HANDLE;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURES_HANDLE;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.HAS_EVALUATION_FEATURES;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.Feature;
import com.datadog.openfeature.internal.ufc.Split;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SplitFeaturesTest {

  @AfterEach
  void tearDown() {
    SplitFeatures.WARNED_FEATURE_DESTINATIONS.clear();
  }

  @Test
  void preparesTheEntriesOnceForTheSplit() {
    final Split split =
        new Split(
            emptyList(),
            "on",
            emptyMap(),
            7,
            asList(
                new Feature("holdout.key", "q4-global", asList("HOOK", "EXPOSURE")),
                new Feature("holdout.weight", 0.5, singletonList("EVALUATION")),
                new Feature("holdout.enabled", true, singletonList("EXPOSURE"))));

    final Map<String, Object> expected = new HashMap<>();
    expected.put(FEATURE_PREFIX + "holdout.key", "q4-global");
    expected.put(EXPOSURE_FEATURE_PREFIX + "holdout.key", "q4-global");
    expected.put(EVALUATION_FEATURE_PREFIX + "holdout.weight", 0.5);
    expected.put(EXPOSURE_FEATURE_PREFIX + "holdout.enabled", true);
    expected.put(HAS_EVALUATION_FEATURES, true);
    final SplitFeatures prepared = split.preparedFeatures(SplitFeatures::prepare);
    expected.put(EVALUATION_FEATURES_HANDLE, prepared.evaluationFeaturesHandle());
    expected.put(EXPOSURE_FEATURES_HANDLE, prepared.exposureFeaturesHandle());
    assertSame(prepared, split.preparedFeatures(SplitFeatures::prepare));
    assertEquals(4, prepared.size());
    assertEquals(expected, metadata(prepared));
    assertEquals(
        singletonMap("holdout.weight", 0.5),
        EvaluationFeatureHandles.lookup(prepared.evaluationFeaturesHandle()),
        "the handle resolves to the evaluation features, without their prefix");
    final Map<String, Object> exposure = new HashMap<>();
    exposure.put("holdout.key", "q4-global");
    exposure.put("holdout.enabled", true);
    assertEquals(
        exposure,
        EvaluationFeatureHandles.lookup(prepared.exposureFeaturesHandle()),
        "the exposure handle resolves to the exposure features, without their prefix");
  }

  @Test
  void splitsWithoutEvaluationFeaturesGetNoHandle() {
    final SplitFeatures features =
        SplitFeatures.prepare(singletonList(new Feature("k", "v", singletonList("HOOK"))));

    assertEquals(0L, features.evaluationFeaturesHandle());
    assertEquals(0L, features.exposureFeaturesHandle());
    assertEquals(singletonMap(FEATURE_PREFIX + "k", "v"), metadata(features));
  }

  @Test
  void keepsTheFirstFeatureOfAKeyAndSkipsIncompleteOrUnsupportedOnes() {
    final SplitFeatures features =
        SplitFeatures.prepare(
            asList(
                null,
                new Feature(null, "x", singletonList("HOOK")),
                new Feature("no.destination", "x", null),
                new Feature("holdout.key", "q4-global", singletonList("HOOK")),
                new Feature("holdout.key", "q1-global", asList("HOOK", "EXPOSURE")),
                new Feature("unsupported", singletonList("x"), singletonList("HOOK"))));

    assertEquals(singletonMap(FEATURE_PREFIX + "holdout.key", "q4-global"), metadata(features));
  }

  @Test
  void storesNumbersAsDoubles() {
    final SplitFeatures features =
        SplitFeatures.prepare(singletonList(new Feature("weight", 1, singletonList("HOOK"))));

    assertEquals(singletonMap(FEATURE_PREFIX + "weight", 1.0), metadata(features));
  }

  @Test
  void marksEvaluationFeaturesEvenWhenTheirValueIsUnsupported() {
    final SplitFeatures features =
        SplitFeatures.prepare(
            singletonList(new Feature("bad", singletonList("x"), singletonList("EVALUATION"))));

    assertEquals(singletonMap(HAS_EVALUATION_FEATURES, true), metadata(features));
  }

  @Test
  void sharesTheEmptyPreparationForSplitsWithoutFeatures() {
    assertSame(SplitFeatures.NONE, SplitFeatures.prepare(null));
    assertSame(SplitFeatures.NONE, SplitFeatures.prepare(emptyList()));
    assertSame(
        SplitFeatures.NONE,
        new Split(emptyList(), "on", emptyMap(), null).preparedFeatures(SplitFeatures::prepare));
  }

  @Test
  void warnsAboutUnknownDestinationsWhenPreparing() {
    final SplitFeatures features =
        SplitFeatures.prepare(
            asList(
                new Feature("holdout.key", "q4-global", asList("HOOK", "NEW_A")),
                new Feature("other", "x", asList("NEW_A", "NEW_B"))));

    assertEquals(
        new HashSet<>(asList("NEW_A", "NEW_B")), SplitFeatures.WARNED_FEATURE_DESTINATIONS);
    assertEquals(singletonMap(FEATURE_PREFIX + "holdout.key", "q4-global"), metadata(features));
    assertTrue(metadata(SplitFeatures.prepare(null)).isEmpty());
  }

  private static Map<String, Object> singletonMap(final String key, final Object value) {
    final Map<String, Object> map = new HashMap<>();
    map.put(key, value);
    return map;
  }

  private static Map<String, Object> metadata(final SplitFeatures features) {
    final ImmutableMetadata.ImmutableMetadataBuilder builder = ImmutableMetadata.builder();
    features.addTo(builder);
    return builder.build().asUnmodifiableMap();
  }
}
