package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.HAS_EVALUATION_FEATURES;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadog.openfeature.ExperimentationOptOutMode;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.ConditionOperator;
import com.datadog.openfeature.internal.ufc.Feature;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ParsedSemver;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void reportsNoExposureWhenExposuresAreDisabled(final boolean enrichment) {
    final FlagEvaluator evaluator =
        new FlagEvaluator(
            enrichment,
            (flag, subject, allocation, variant, serialId) -> {
              throw new AssertionError("no exposure lookup expected");
            },
            ExperimentationOptOutMode.DISABLED,
            false);

    final Map<String, Object> metadata = evaluate(evaluator, true, 7, null);

    assertEquals("allocation", metadata.get(ALLOCATION_KEY), "assignment is unchanged");
    assertFalse(metadata.containsKey(EXPOSURE_CACHE_HIT));
    assertEquals(enrichment, metadata.containsKey(DO_LOG));
    if (enrichment) {
      assertEquals(Boolean.FALSE, metadata.get(DO_LOG), "span enrichment sees no exposure");
    }
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

  private static final int SEMVER_STEPS = 10;

  @TableTest({
    "scenario                    | appVersion                                             | osVersion | variant",
    "lowest step                 | 1.0.0                                                  | 2.1.0     | v0     ",
    "middle step                 | 1.4.9                                                  | 2.1.0     | v4     ",
    "prerelease precedes release | 1.5.0-beta.11.rc-7+build.20261117.sha.0123456789abcdef | 2.1.0     | v4     ",
    "release of the prerelease   | 1.5.0                                                  | 2.1.0     | v5     ",
    "omitted patch               | 1.3                                                    | 2.1.0     | v3     ",
    "highest step                | 1.9.99                                                 | 2.1.0     | v9     ",
    "above the ladder            | 1.10.0                                                 | 2.1.0     | none   ",
    "below the ladder            | 0.9.0                                                  | 2.1.0     | none   ",
    "invalid version             | not-a-version                                          | 2.1.0     | none   ",
    "other attribute fails       | 1.4.9                                                  | 1.9.0     | none   ",
    "other attribute invalid     | 1.4.9                                                  | 2.x       | none   "
  })
  void evaluatesSemverLadderSteps(
      final String appVersion, final String osVersion, final String variant) throws IOException {
    final MutableContext context = new MutableContext("user");
    context.add("app_version", appVersion);
    context.add("os_version", osVersion);

    final String value =
        new FlagEvaluator(false, (flag, subject, allocation, v, serialId) -> false)
            .evaluate(semverLadder(), String.class, "ladder", "default", context)
            .getValue();

    assertEquals(variant, value);
  }

  /**
   * A flag whose step {@code i} matches {@code 1.i.0 <= app_version < 1.(i+1).0} and {@code
   * os_version >= 2.0.0}, then falls back to {@code none}. Each evaluation tests the same
   * attributes many times.
   */
  private static ServerConfiguration semverLadder() throws IOException {
    final StringBuilder allocations = new StringBuilder();
    final StringBuilder variations =
        new StringBuilder("\"none\":{\"key\":\"none\",\"value\":\"none\"}");
    for (int i = 0; i < SEMVER_STEPS; i++) {
      variations.append(",\"v").append(i).append("\":{\"key\":\"v").append(i);
      variations.append("\",\"value\":\"v").append(i).append("\"}");
      allocations
          .append("{\"key\":\"step-")
          .append(i)
          .append("\",\"rules\":[{\"conditions\":[")
          .append(semverCondition("app_version", "SEMVER_GTE", "1." + i + ".0"))
          .append(',')
          .append(semverCondition("app_version", "SEMVER_LT", "1." + (i + 1) + ".0"))
          .append(',')
          .append(semverCondition("os_version", "SEMVER_GTE", "2.0.0"))
          .append("]}],\"splits\":[{\"variationKey\":\"v")
          .append(i)
          .append("\",\"shards\":[]}],\"doLog\":false},");
    }
    allocations.append(
        "{\"key\":\"fallback\",\"splits\":[{\"variationKey\":\"none\",\"shards\":[]}],"
            + "\"doLog\":false}");
    final String json =
        "{\"flags\":{"
            + "\"ladder\":{\"key\":\"ladder\",\"enabled\":true,\"variationType\":\"STRING\","
            + "\"variations\":{"
            + variations
            + "},\"allocations\":["
            + allocations
            + "]}}}";
    return UniversalFlagConfigParser.parse(json.getBytes(UTF_8));
  }

  private static String semverCondition(
      final String attribute, final String operator, final String value) {
    return "{\"attribute\":\""
        + attribute
        + "\",\"operator\":\""
        + operator
        + "\",\"value\":\""
        + value
        + "\"}";
  }

  @TableTest({
    "scenario           | attribute | expectedCode ",
    "non-numeric string | abc       | TYPE_MISMATCH",
    "empty string       | ''        | TYPE_MISMATCH",
    "boolean            | true      | TYPE_MISMATCH",
    "decimal comma      | 1,5       | TYPE_MISMATCH"
  })
  void reportsNumericConversionErrorsAsMapValueDoes(
      final String attribute, final ErrorCode expectedCode) {
    final Object value = "true".equals(attribute) ? Boolean.TRUE : attribute;
    final MutableContext context = new MutableContext("user");
    if (value instanceof Boolean) {
      context.add("score", (Boolean) value);
    } else {
      context.add("score", (String) value);
    }

    final ProviderEvaluation<Integer> result =
        evaluateCondition(new ConditionConfiguration(ConditionOperator.GTE, "score", 4.0), context);

    assertEquals(expectedCode, result.getErrorCode());
    assertEquals(mapValueFailure(value).getMessage(), result.getErrorMessage());
  }

  @Test
  void reportsANullListElementAsMapValueDoes() {
    final MutableContext context = new MutableContext("user");
    context.add("score", 5);

    final ProviderEvaluation<Integer> result =
        evaluateCondition(
            new ConditionConfiguration(
                ConditionOperator.ONE_OF, "score", singletonList((Object) null)),
            context);

    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    final String expectedMessage = mapValueFailure(null).getMessage();
    // Before JDK 15 a NullPointerException has no message, so there is nothing to compare.
    assumeTrue(expectedMessage != null, "helpful NullPointerException messages are unavailable");
    assertEquals(expectedMessage, result.getErrorMessage());
  }

  @Test
  void comparesPaddedNumericStrings() {
    final MutableContext context = new MutableContext("user");
    context.add("score", " 4 ");

    final ProviderEvaluation<Integer> result =
        evaluateCondition(new ConditionConfiguration(ConditionOperator.GTE, "score", 4.0), context);

    assertNull(result.getErrorCode());
    assertEquals(Integer.valueOf(1), result.getValue());
  }

  /** The failure of the numeric conversion that comparisons used before they stopped boxing. */
  private static Exception mapValueFailure(final Object value) {
    return assertThrows(
        Exception.class,
        () -> {
          final double unused = FlagEvaluator.mapValue(Double.class, value);
        });
  }

  /** Evaluates a flag with one rule of one condition, with full evaluation data observed. */
  private static ProviderEvaluation<Integer> evaluateCondition(
      final ConditionConfiguration condition, final MutableContext context) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    final Split split = new Split(emptyList(), "on", emptyMap(), null, null);
    final Allocation allocation =
        new Allocation(
            "allocation",
            singletonList(new Rule(singletonList(condition))),
            null,
            null,
            singletonList(split),
            false);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "flag", new Flag("flag", true, ValueType.INTEGER, variations, singletonList(allocation)));
    return new FlagEvaluator(false, (flag, subject, alloc, variant, serialId) -> false)
        .evaluate(
            new ServerConfiguration("", "", true, null, flags), Integer.class, "flag", 0, context);
  }

  @TableTest({
    "scenario                     | operator   | attribute                | comparand  | matches",
    "decided less, then invalid   | SEMVER_LT  | 0.9.0-                   | 1.0.0      | false  ",
    "decided less, then overflow  | SEMVER_LTE | 0.18446744073709551616.0 | 1.0.0      | false  ",
    "invalid is never unequal     | SEMVER_NEQ | 1.0.0x                   | 1.0.0      | false  ",
    "extra component decides      | SEMVER_GT  | 1.0.0.1                  | 1.0.0      | true   ",
    "comparand extra component    | SEMVER_LT  | 1.0.0                    | 1.0.0.1    | true   ",
    "prerelease then dotted build | SEMVER_EQ  | 1.0.0-rc.1+build.2       | 1.0.0-rc.1 | true   "
  })
  void evaluatesSemverConditionsOnTheAttributeString(
      final ConditionOperator operator,
      final String attribute,
      final String comparand,
      final boolean matches) {
    final ConditionConfiguration condition =
        new ConditionConfiguration(operator, "version", comparand);
    condition.semverComparand = ParsedSemver.parse(comparand);
    final MutableContext context = new MutableContext("user");
    context.add("version", attribute);

    final ProviderEvaluation<Integer> result = evaluateCondition(condition, context);

    assertNull(result.getErrorCode());
    assertEquals(Integer.valueOf(matches ? 1 : 0), result.getValue());
  }

  @Test
  void comparesFractionalNumbersWithoutTruncating() {
    final MutableContext context = new MutableContext("user");
    context.add("score", 4.5);

    final ProviderEvaluation<Integer> result =
        evaluateCondition(new ConditionConfiguration(ConditionOperator.GT, "score", 4.2), context);

    assertNull(result.getErrorCode());
    assertEquals(Integer.valueOf(1), result.getValue());
  }
}
