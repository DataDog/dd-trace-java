package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.ufc.ConditionOperator.NOT_ONE_OF;
import static com.datadog.openfeature.internal.ufc.ConditionOperator.ONE_OF;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.EvaluatorParams;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DependentFlagEvaluatorTest {

  @Test
  void memoizesDependenciesAndReplaysTheirExposureAfterRuleRollback() {
    final List<String> evaluations = new ArrayList<>();
    final List<String> exposures = new ArrayList<>();
    final FlagEvaluator evaluator =
        evaluator(
            evaluations,
            (items, context) -> {
              for (final FlagEvaluator.DependencyExposure item : items) {
                exposures.add(item.flagKey);
              }
            });
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("dependency", stringFlag("dependency", "on", null, true));
    flags.put(
        "root",
        integerFlag(
            "root",
            Arrays.asList(
                allocation("rejected", dependencyRule("dependency", "off"), "selected", false),
                allocation("accepted", dependencyRule("dependency", "on"), "selected", false))));

    final ProviderEvaluation<Integer> result =
        evaluator.evaluate(config(flags, null), Integer.class, "root", 0, new MutableContext("u"));

    assertEquals(1, result.getValue());
    assertEquals(singletonList("dependency:on"), evaluations);
    assertEquals(singletonList("dependency"), exposures);
  }

  @Test
  void rollsBackDependencyExposuresWhenTheRootEvaluationFails() {
    final List<String> evaluations = new ArrayList<>();
    final List<List<FlagEvaluator.DependencyExposure>> exposureCommits = new ArrayList<>();
    final FlagEvaluator evaluator =
        evaluator(evaluations, (items, context) -> exposureCommits.add(items));
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("dependency", stringFlag("dependency", "on", null, true));
    flags.put(
        "root",
        integerFlag(
            "root",
            singletonList(
                allocation("broken", dependencyRule("dependency", "on"), "missing", false))));

    final ProviderEvaluation<Integer> result =
        evaluator.evaluate(config(flags, null), Integer.class, "root", 0, new MutableContext("u"));

    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals(singletonList("dependency:on"), evaluations);
    assertTrue(exposureCommits.isEmpty());
  }

  @Test
  void propagatesCyclesAndEmitsAnErrorForEveryReachedDependency() {
    final List<String> evaluations = new ArrayList<>();
    final FlagEvaluator evaluator = evaluator(evaluations, (items, context) -> {});
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("a", stringFlag("a", "on", dependencyRule("b", "on"), false));
    flags.put("b", stringFlag("b", "on", dependencyRule("a", "on"), false));

    final ProviderEvaluation<String> result =
        evaluator.evaluate(
            config(flags, null), String.class, "a", "default", new MutableContext("u"));

    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals(singletonList("b:GENERAL"), evaluations);
  }

  @Test
  void enforcesTheConfiguredMaximumDependencyDepth() {
    final List<String> evaluations = new ArrayList<>();
    final FlagEvaluator evaluator = evaluator(evaluations, (items, context) -> {});
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("root", stringFlag("root", "on", dependencyRule("a", "on"), false));
    flags.put("a", stringFlag("a", "on", dependencyRule("b", "on"), false));
    flags.put("b", stringFlag("b", "on", null, false));

    final ProviderEvaluation<String> result =
        evaluator.evaluate(
            config(flags, new EvaluatorParams(1D)),
            String.class,
            "root",
            "default",
            new MutableContext("u"));

    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals(Arrays.asList("b:GENERAL", "a:GENERAL"), evaluations);
  }

  @Test
  void memoizedSubtreeDepthFailureDoesNotReemitMemoizedNodeInEitherOrder() {
    for (final List<String> rootDependencies :
        Arrays.asList(Arrays.asList("a", "b"), Arrays.asList("b", "a"))) {
      final List<String> evaluations = new ArrayList<>();
      final List<String> exposures = new ArrayList<>();
      final FlagEvaluator evaluator =
          evaluator(
              evaluations,
              (items, context) -> {
                for (final FlagEvaluator.DependencyExposure item : items) {
                  exposures.add(item.flagKey);
                }
              });
      final Map<String, Flag> flags = new HashMap<>();
      flags.put("leaf", stringFlag("leaf", "on", null, true));
      flags.put("a", stringFlag("a", "on", dependencyRule("leaf", "on"), true));
      flags.put("b", stringFlag("b", "on", dependencyRule("a", "on"), true));
      final Rule rootRule =
          new Rule(
              Arrays.asList(
                  new ConditionConfiguration(
                      ONE_OF, null, rootDependencies.get(0), singletonList("on")),
                  new ConditionConfiguration(
                      ONE_OF, null, rootDependencies.get(1), singletonList("on"))));
      flags.put("root", stringFlag("root", "on", rootRule, true));

      final ProviderEvaluation<String> result =
          evaluator.evaluate(
              config(flags, new EvaluatorParams(2D)),
              String.class,
              "root",
              "default",
              new MutableContext("u"));

      assertEquals(ErrorCode.GENERAL, result.getErrorCode(), rootDependencies.toString());
      final List<String> evaluationKeys = new ArrayList<>();
      for (final String evaluation : evaluations) {
        evaluationKeys.add(evaluation.substring(0, evaluation.indexOf(':')));
      }
      assertEquals(Arrays.asList("leaf", "a", "b"), evaluationKeys);
      assertTrue(exposures.isEmpty());
    }
  }

  @Test
  void validatesInvalidMaximumDependencyDepthOnlyWhenADependencyIsReached() {
    final FlagEvaluator evaluator = evaluator(new ArrayList<>(), (items, context) -> {});
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("ordinary", stringFlag("ordinary", "on", null, false));
    flags.put("dependency", stringFlag("dependency", "on", null, false));
    flags.put(
        "dependent", stringFlag("dependent", "on", dependencyRule("dependency", "on"), false));
    final ServerConfiguration config = config(flags, new EvaluatorParams(1.5D));

    final ProviderEvaluation<String> ordinary =
        evaluator.evaluate(config, String.class, "ordinary", "default", new MutableContext("u"));
    final ProviderEvaluation<String> dependent =
        evaluator.evaluate(config, String.class, "dependent", "default", new MutableContext("u"));

    assertEquals("on-value", ordinary.getValue());
    assertEquals(ErrorCode.PARSE_ERROR, dependent.getErrorCode());
    assertNotNull(dependent.getFlagMetadata());
  }

  @Test
  void rejectsExplicitNullMaximumDependencyDepthButDefaultsAnEmptyObject() {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("ordinary", stringFlag("ordinary", "on", null, false));
    flags.put("dependency", stringFlag("dependency", "on", null, false));
    flags.put(
        "dependent", stringFlag("dependent", "on", dependencyRule("dependency", "on"), false));
    final FlagEvaluator evaluator = evaluator(new ArrayList<>(), (items, context) -> {});

    assertEquals(
        "on-value",
        evaluator
            .evaluate(
                config(flags, new EvaluatorParams(null, true)),
                String.class,
                "ordinary",
                "default",
                new MutableContext("u"))
            .getValue());
    assertEquals(
        ErrorCode.PARSE_ERROR,
        evaluator
            .evaluate(
                config(flags, new EvaluatorParams(null, true)),
                String.class,
                "dependent",
                "default",
                new MutableContext("u"))
            .getErrorCode());
    assertEquals(
        "on-value",
        evaluator
            .evaluate(
                config(flags, new EvaluatorParams(null, false)),
                String.class,
                "dependent",
                "default",
                new MutableContext("u"))
            .getValue());
  }

  @Test
  void rejectsNonStringDependencyMembershipOperandsWithParseError() throws Exception {
    final ServerConfiguration config =
        UniversalFlagConfigParser.parse(
            ("{\"flags\":{\"root\":{\"key\":\"root\",\"enabled\":true,"
                    + "\"variationType\":\"STRING\",\"variations\":{\"on\":{\"key\":\"on\","
                    + "\"value\":\"on-value\"}},\"allocations\":[{\"key\":\"allocation\","
                    + "\"rules\":[{\"conditions\":[{\"operator\":\"ONE_OF\","
                    + "\"flagEvaluation\":{\"key\":\"dependency\"},\"value\":[\"on\",1]}]}],"
                    + "\"splits\":[{\"variationKey\":\"on\",\"shards\":[]}]}]}}}")
                .getBytes(UTF_8));

    final ProviderEvaluation<String> result =
        evaluator(new ArrayList<>(), (items, context) -> {})
            .evaluate(config, String.class, "root", "default", new MutableContext("u"));

    assertEquals(ErrorCode.PARSE_ERROR, result.getErrorCode());
  }

  @Test
  void supportsNotOneOfAndShortCircuitsBeforeAFatalDependency() {
    final List<String> evaluations = new ArrayList<>();
    final FlagEvaluator evaluator = evaluator(evaluations, (items, context) -> {});
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("dependency", stringFlag("dependency", "on", null, false));
    final Rule rejectedBeforeMissing =
        new Rule(
            Arrays.asList(
                new ConditionConfiguration(ONE_OF, "gate", null, singletonList("open")),
                new ConditionConfiguration(ONE_OF, null, "missing", singletonList("on"))));
    flags.put(
        "root",
        integerFlag(
            "root",
            Arrays.asList(
                allocation("short-circuit", rejectedBeforeMissing, "selected", false),
                allocation(
                    "not-one-of",
                    new Rule(
                        singletonList(
                            new ConditionConfiguration(
                                NOT_ONE_OF, null, "dependency", singletonList("off")))),
                    "selected",
                    false))));
    final MutableContext context = new MutableContext("u");
    context.add("gate", "closed");

    final ProviderEvaluation<Integer> result =
        evaluator.evaluate(config(flags, null), Integer.class, "root", 0, context);

    assertEquals(1, result.getValue());
    assertEquals(singletonList("dependency:on"), evaluations);
  }

  @Test
  void emitsMissingAndInvalidPrerequisiteErrors() {
    final List<String> missing = new ArrayList<>();
    final Map<String, Flag> missingFlags = new HashMap<>();
    missingFlags.put("root", stringFlag("root", "on", dependencyRule("missing", "on"), false));
    assertEquals(
        ErrorCode.FLAG_NOT_FOUND,
        evaluator(missing, (items, context) -> {})
            .evaluate(
                config(missingFlags, null),
                String.class,
                "root",
                "default",
                new MutableContext("u"))
            .getErrorCode());
    assertEquals(singletonList("missing:FLAG_NOT_FOUND"), missing);

    final List<String> invalid = new ArrayList<>();
    final ServerConfiguration invalidConfig = config(missingFlags, null);
    invalidConfig.invalidFlags = singletonMap("missing", "invalid_flag");
    assertEquals(
        ErrorCode.PARSE_ERROR,
        evaluator(invalid, (items, context) -> {})
            .evaluate(invalidConfig, String.class, "root", "default", new MutableContext("u"))
            .getErrorCode());
    assertEquals(singletonList("missing:PARSE_ERROR"), invalid);
  }

  @Test
  void rollsBackATransitiveTraceWhenTheRootAllocationHasNoAssignment() {
    final List<String> evaluations = new ArrayList<>();
    final List<String> exposures = new ArrayList<>();
    final FlagEvaluator evaluator =
        evaluator(
            evaluations,
            (items, context) -> {
              for (final FlagEvaluator.DependencyExposure item : items) {
                exposures.add(item.flagKey);
              }
            });
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("leaf", stringFlag("leaf", "on", null, true));
    flags.put("parent", stringFlag("parent", "on", dependencyRule("leaf", "on"), true));
    final Allocation noAssignment =
        new Allocation(
            "no-assignment",
            singletonList(dependencyRule("parent", "on")),
            null,
            null,
            emptyList(),
            false);
    flags.put(
        "root",
        integerFlag(
            "root", Arrays.asList(noAssignment, allocation("fallback", null, "selected", false))));

    final ProviderEvaluation<Integer> result =
        evaluator.evaluate(config(flags, null), Integer.class, "root", 0, new MutableContext("u"));

    assertEquals(1, result.getValue());
    assertEquals(Arrays.asList("leaf:on", "parent:on"), evaluations);
    assertTrue(exposures.isEmpty());
  }

  private static FlagEvaluator evaluator(
      final List<String> evaluations, final FlagEvaluator.DependencyExposureSink exposureSink) {
    return new FlagEvaluator(
        false,
        (flag, subject, allocation, variant, serialId) -> false,
        (flag, details, context, timestamp) ->
            evaluations.add(
                flag
                    + ":"
                    + (details.getErrorCode() == null
                        ? details.getVariant()
                        : details.getErrorCode().name())),
        exposureSink);
  }

  private static ServerConfiguration config(
      final Map<String, Flag> flags, final EvaluatorParams params) {
    return new ServerConfiguration("", "", false, null, flags, params);
  }

  private static Flag stringFlag(
      final String key, final String variant, final Rule rule, final boolean doLog) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put(variant, new Variant(variant, variant + "-value"));
    return new Flag(
        key,
        true,
        ValueType.STRING,
        variations,
        singletonList(allocation(key + "-allocation", rule, variant, doLog)));
  }

  private static Flag integerFlag(final String key, final List<Allocation> allocations) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("selected", new Variant("selected", 1));
    return new Flag(key, true, ValueType.INTEGER, variations, allocations);
  }

  private static Allocation allocation(
      final String key, final Rule rule, final String variant, final boolean doLog) {
    final List<Rule> rules = rule == null ? null : singletonList(rule);
    return new Allocation(
        key,
        rules,
        null,
        null,
        singletonList(new Split(emptyList(), variant, emptyMap(), 1)),
        doLog);
  }

  private static Rule dependencyRule(final String flagKey, final String expectedVariant) {
    return new Rule(
        singletonList(
            new ConditionConfiguration(ONE_OF, null, flagKey, singletonList(expectedVariant))));
  }
}
