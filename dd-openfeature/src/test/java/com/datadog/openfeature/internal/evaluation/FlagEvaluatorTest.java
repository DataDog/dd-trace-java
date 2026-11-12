package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.HAS_EVALUATION_FEATURES;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Feature;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.MutableContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class FlagEvaluatorTest {

  @AfterEach
  void tearDown() {
    FlagEvaluator.WARNED_FEATURE_DESTINATIONS.clear();
  }

  @TableTest({
    "scenario                   | enrichment | doLog | serialId | hasDoLog | hasSerialId",
    "neither enrichment nor log | false      | false | 7        | false    | false      ",
    "log without enrichment     | false      | true  | 7        | true     | true       ",
    "enrichment without log     | true       | false | 7        | true     | true       ",
    "log without serial id      | false      | true  |          | true     | false      ",
    "enrichment and log         | true       | true  | 0        | true     | true       "
  })
  void emitsTheExposureMetadataWhenEnrichingSpansOrLoggingExposures(
      final boolean enrichment,
      final boolean doLog,
      final Integer serialId,
      final boolean hasDoLog,
      final boolean hasSerialId) {
    final Map<String, Object> metadata =
        evaluate(new FlagEvaluator(enrichment, notExposed()), doLog, serialId, null);

    assertEquals(hasDoLog, metadata.containsKey(DO_LOG));
    if (hasDoLog) {
      assertEquals(doLog, metadata.get(DO_LOG));
    }
    assertEquals(hasSerialId ? serialId : null, metadata.get(SPLIT_SERIAL_ID));
    assertEquals(doLog, metadata.containsKey(EXPOSURE_CACHE_HIT));
  }

  @Test
  void stampsWhetherTheExposureWasAlreadyRecorded() {
    final List<String> lookups = new ArrayList<>();
    final FlagEvaluator evaluator =
        new FlagEvaluator(
            false,
            (flag, subject, allocation, variant, serialId) -> {
              lookups.add(flag + "/" + subject + "/" + allocation + "/" + variant + "/" + serialId);
              return "user-2".equals(subject);
            });

    assertEquals(Boolean.FALSE, evaluate(evaluator, "user-1").get(EXPOSURE_CACHE_HIT));
    assertEquals(Boolean.TRUE, evaluate(evaluator, "user-2").get(EXPOSURE_CACHE_HIT));
    assertEquals(asList("flag/user-1/allocation/on/7", "flag/user-2/allocation/on/7"), lookups);
  }

  @Test
  void doesNotLookUpExposuresForAnAllocationThatDoesNotLogThem() {
    final FlagEvaluator evaluator =
        new FlagEvaluator(
            true,
            (flag, subject, allocation, variant, serialId) -> {
              throw new AssertionError("no exposure lookup expected");
            });

    assertFalse(evaluate(evaluator, false, 7, null).containsKey(EXPOSURE_CACHE_HIT));
  }

  @Test
  void routesEachFeatureToTheDestinationsItLists() {
    final Map<String, Object> metadata =
        evaluate(
            new FlagEvaluator(false, notExposed()),
            false,
            null,
            asList(
                new Feature("holdout.key", "q4-global", singletonList("HOOK")),
                new Feature("bandit.arm", "a", singletonList("EXPOSURE")),
                new Feature("holdout.weight", 1, asList("HOOK", "EVALUATION")),
                new Feature("holdout.enabled", true, singletonList("EXPOSURE"))));

    assertEquals("q4-global", metadata.get(FEATURE_PREFIX + "holdout.key"));
    assertEquals("a", metadata.get(EXPOSURE_FEATURE_PREFIX + "bandit.arm"));
    assertEquals(1.0, metadata.get(FEATURE_PREFIX + "holdout.weight"));
    assertEquals(1.0, metadata.get(EVALUATION_FEATURE_PREFIX + "holdout.weight"));
    assertEquals(true, metadata.get(EXPOSURE_FEATURE_PREFIX + "holdout.enabled"));
    assertEquals(true, metadata.get(HAS_EVALUATION_FEATURES));
  }

  @Test
  void marksEvaluationFeaturesOnlyWhenTheSplitHasSome() {
    final Map<String, Object> metadata =
        evaluate(
            new FlagEvaluator(false, notExposed()),
            false,
            null,
            singletonList(new Feature("holdout.key", "q4-global", singletonList("HOOK"))));

    assertFalse(metadata.containsKey(HAS_EVALUATION_FEATURES));
  }

  @Test
  void keepsTheFirstFeatureOfARepeatedKeyAndSkipsIncompleteOnes() {
    final Map<String, Object> metadata =
        evaluate(
            new FlagEvaluator(false, notExposed()),
            false,
            null,
            asList(
                null,
                new Feature(null, "x", singletonList("HOOK")),
                new Feature("no.destination", "x", null),
                new Feature("holdout.key", "q4-global", singletonList("HOOK")),
                new Feature("holdout.key", "q1-global", asList("HOOK", "EXPOSURE")),
                new Feature("unsupported", singletonList("x"), singletonList("HOOK"))));

    assertEquals("q4-global", metadata.get(FEATURE_PREFIX + "holdout.key"));
    assertFalse(metadata.containsKey(EXPOSURE_FEATURE_PREFIX + "holdout.key"));
    assertFalse(metadata.containsKey(FEATURE_PREFIX + "no.destination"));
    assertFalse(metadata.containsKey(FEATURE_PREFIX + "unsupported"));
  }

  @Test
  void warnsOnceForEachUnknownDestination() {
    final FlagEvaluator evaluator = new FlagEvaluator(false, notExposed());
    final List<Feature> features =
        asList(
            new Feature("holdout.key", "q4-global", asList("HOOK", "NEW_A")),
            new Feature("other", "x", asList("NEW_A", "NEW_B", "EXPOSURE")));

    evaluate(evaluator, true, 7, features);
    final Map<String, Object> metadata = evaluate(evaluator, true, 7, features);

    assertEquals(
        new HashSet<>(asList("NEW_A", "NEW_B")), FlagEvaluator.WARNED_FEATURE_DESTINATIONS);
    assertEquals("q4-global", metadata.get(FEATURE_PREFIX + "holdout.key"));
    assertEquals("x", metadata.get(EXPOSURE_FEATURE_PREFIX + "other"));
  }

  @Test
  void stopsRememberingUnknownDestinationsAtTheCap() {
    for (int i = 0; i < FlagEvaluator.MAX_WARNED_FEATURE_DESTINATIONS; i++) {
      FlagEvaluator.WARNED_FEATURE_DESTINATIONS.add("OLD_" + i);
    }

    final Map<String, Object> metadata =
        evaluate(
            new FlagEvaluator(false, notExposed()),
            true,
            7,
            singletonList(new Feature("holdout.key", "q4-global", asList("HOOK", "NEW_A"))));

    assertEquals(
        FlagEvaluator.MAX_WARNED_FEATURE_DESTINATIONS,
        FlagEvaluator.WARNED_FEATURE_DESTINATIONS.size());
    assertFalse(FlagEvaluator.WARNED_FEATURE_DESTINATIONS.contains("NEW_A"));
    assertEquals("q4-global", metadata.get(FEATURE_PREFIX + "holdout.key"));
  }

  @Test
  void addsNoFeaturesWhenTheSplitHasNone() {
    final Map<String, Object> metadata = evaluate(new FlagEvaluator(false, notExposed()), "user-1");

    for (final String key : metadata.keySet()) {
      assertFalse(key.startsWith(FEATURE_PREFIX), key);
      assertFalse(key.startsWith(EXPOSURE_FEATURE_PREFIX), key);
      assertFalse(key.startsWith(EVALUATION_FEATURE_PREFIX), key);
    }
    assertNull(metadata.get(HAS_EVALUATION_FEATURES));
    assertTrue(metadata.containsKey(DO_LOG));
  }

  private static FlagEvaluator.ExposureLookup notExposed() {
    return (flag, subject, allocation, variant, serialId) -> false;
  }

  private static Map<String, Object> evaluate(
      final FlagEvaluator evaluator, final String targetingKey) {
    return evaluate(evaluator, true, 7, null, targetingKey);
  }

  private static Map<String, Object> evaluate(
      final FlagEvaluator evaluator,
      final boolean doLog,
      final Integer serialId,
      final List<Feature> features) {
    return evaluate(evaluator, doLog, serialId, features, "user-1");
  }

  private static Map<String, Object> evaluate(
      final FlagEvaluator evaluator,
      final boolean doLog,
      final Integer serialId,
      final List<Feature> features,
      final String targetingKey) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    final Split split = new Split(emptyList(), "on", emptyMap(), serialId, features);
    final Allocation allocation =
        new Allocation("allocation", null, null, null, singletonList(split), doLog);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "flag", new Flag("flag", true, ValueType.INTEGER, variations, singletonList(allocation)));
    return evaluator
        .evaluate(
            new ServerConfiguration("", "", false, null, flags),
            Integer.class,
            "flag",
            0,
            new MutableContext(targetingKey))
        .getFlagMetadata()
        .asUnmodifiableMap();
  }
}
