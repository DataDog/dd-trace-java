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

import com.datadog.openfeature.ExperimentationOptOutMode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DependentFlagEvaluatorTest {

  @Test
  void appliesFutureExperimentOptOutToDependenciesUsingTheRootCutoff() {
    final FlagEvaluator evaluator = evaluator(ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY);
    final Map<String, Variant> dependencyVariations = new HashMap<>();
    dependencyVariations.put("future", new Variant("future", "future-value"));
    dependencyVariations.put("on", new Variant("on", "on-value"));
    final Allocation future =
        new Allocation(
            "future",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "future", emptyMap(), 1)),
            true,
            Instant.parse("2026-10-20T00:00:00Z"));
    final Allocation retained =
        new Allocation(
            "retained",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 1)),
            true,
            Instant.parse("2026-10-18T00:00:00Z"));
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "dependency",
        new Flag(
            "dependency",
            true,
            ValueType.STRING,
            dependencyVariations,
            Arrays.asList(future, retained)));
    flags.put("root", stringFlag("root", "on", dependencyRule("dependency", "on"), false));
    final MutableContext context = new MutableContext("u");
    context.add(ExperimentationOptOut.ATTRIBUTE, "2026-10-19T12:00:00Z");

    final FlagEvaluator.EvaluationResult<String> evaluation =
        evaluator.evaluateWithTrace(config(flags, null), String.class, "root", "default", context);

    assertEquals("on-value", evaluation.details.getValue());
    assertEquals(singletonList("dependency:on"), evaluations(evaluation));
    assertEquals(singletonList("dependency"), exposures(evaluation));
  }

  @Test
  void propagatesInvalidDependencyActivationUnderFutureExperimentOptOut() {
    final FlagEvaluator evaluator = evaluator(ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY);
    final Map<String, Variant> dependencyVariations = new HashMap<>();
    dependencyVariations.put("on", new Variant("on", "on-value"));
    final Allocation invalid =
        new Allocation(
            "invalid",
            null,
            null,
            null,
            singletonList(new Split(emptyList(), "on", emptyMap(), 1)),
            true,
            null,
            true);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "dependency",
        new Flag(
            "dependency", true, ValueType.STRING, dependencyVariations, singletonList(invalid)));
    flags.put("root", stringFlag("root", "on", dependencyRule("dependency", "on"), false));
    final MutableContext context = new MutableContext("u");
    context.add(ExperimentationOptOut.ATTRIBUTE, "2026-10-19T12:00:00Z");

    final FlagEvaluator.EvaluationResult<String> evaluation =
        evaluator.evaluateWithTrace(config(flags, null), String.class, "root", "default", context);

    assertEquals(ErrorCode.GENERAL, evaluation.details.getErrorCode());
    assertEquals(singletonList("dependency:PARSE_ERROR"), evaluations(evaluation));
    assertTrue(evaluation.dependencyExposures.isEmpty());
  }

  @Test
  void memoizesDependenciesAndReplaysTheirExposureAfterRuleRollback() {
    final FlagEvaluator evaluator = evaluator();
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("dependency", stringFlag("dependency", "on", null, true));
    flags.put(
        "root",
        integerFlag(
            "root",
            Arrays.asList(
                allocation("rejected", dependencyRule("dependency", "off"), "selected", false),
                allocation("accepted", dependencyRule("dependency", "on"), "selected", false))));

    final FlagEvaluator.EvaluationResult<Integer> evaluation =
        evaluator.evaluateWithTrace(
            config(flags, null), Integer.class, "root", 0, new MutableContext("u"));
    final ProviderEvaluation<Integer> result = evaluation.details;

    assertEquals(1, result.getValue());
    assertEquals(singletonList("dependency:on"), evaluations(evaluation));
    assertEquals(singletonList("dependency"), exposures(evaluation));
  }

  @Test
  void rollsBackDependencyExposuresWhenTheRootEvaluationFails() {
    final FlagEvaluator evaluator = evaluator();
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("dependency", stringFlag("dependency", "on", null, true));
    flags.put(
        "root",
        integerFlag(
            "root",
            singletonList(
                allocation("broken", dependencyRule("dependency", "on"), "missing", false))));

    final FlagEvaluator.EvaluationResult<Integer> evaluation =
        evaluator.evaluateWithTrace(
            config(flags, null), Integer.class, "root", 0, new MutableContext("u"));
    final ProviderEvaluation<Integer> result = evaluation.details;

    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals(singletonList("dependency:on"), evaluations(evaluation));
    assertTrue(evaluation.dependencyExposures.isEmpty());
  }

  @Test
  void propagatesCyclesAndEmitsAnErrorForEveryReachedDependency() {
    final FlagEvaluator evaluator = evaluator();
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("a", stringFlag("a", "on", dependencyRule("b", "on"), false));
    flags.put("b", stringFlag("b", "on", dependencyRule("a", "on"), false));

    final FlagEvaluator.EvaluationResult<String> evaluation =
        evaluator.evaluateWithTrace(
            config(flags, null), String.class, "a", "default", new MutableContext("u"));
    final ProviderEvaluation<String> result = evaluation.details;

    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals(singletonList("b:GENERAL"), evaluations(evaluation));
  }

  @Test
  void enforcesTheConfiguredMaximumDependencyDepth() {
    final FlagEvaluator evaluator = evaluator();
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("root", stringFlag("root", "on", dependencyRule("a", "on"), false));
    flags.put("a", stringFlag("a", "on", dependencyRule("b", "on"), false));
    flags.put("b", stringFlag("b", "on", null, false));

    final FlagEvaluator.EvaluationResult<String> evaluation =
        evaluator.evaluateWithTrace(
            config(flags, new EvaluatorParams(1D)),
            String.class,
            "root",
            "default",
            new MutableContext("u"));
    final ProviderEvaluation<String> result = evaluation.details;

    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals(Arrays.asList("b:GENERAL", "a:GENERAL"), evaluations(evaluation));
  }

  @Test
  void memoizedSubtreeDepthFailureDoesNotReemitMemoizedNodeInEitherOrder() {
    for (final List<String> rootDependencies :
        Arrays.asList(Arrays.asList("a", "b"), Arrays.asList("b", "a"))) {
      final FlagEvaluator evaluator = evaluator();
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

      final FlagEvaluator.EvaluationResult<String> evaluation =
          evaluator.evaluateWithTrace(
              config(flags, new EvaluatorParams(2D)),
              String.class,
              "root",
              "default",
              new MutableContext("u"));
      final ProviderEvaluation<String> result = evaluation.details;

      assertEquals(ErrorCode.GENERAL, result.getErrorCode(), rootDependencies.toString());
      final List<String> evaluationKeys = new ArrayList<>();
      for (final String record : evaluations(evaluation)) {
        evaluationKeys.add(record.substring(0, record.indexOf(':')));
      }
      assertEquals(Arrays.asList("leaf", "a", "b"), evaluationKeys);
      assertTrue(evaluation.dependencyExposures.isEmpty());
    }
  }

  @Test
  void validatesInvalidMaximumDependencyDepthOnlyWhenADependencyIsReached() {
    final FlagEvaluator evaluator = evaluator();
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("ordinary", stringFlag("ordinary", "on", null, false));
    flags.put("dependency", stringFlag("dependency", "on", null, false));
    flags.put(
        "dependent", stringFlag("dependent", "on", dependencyRule("dependency", "on"), false));
    final ServerConfiguration config = config(flags, new EvaluatorParams(1.5D));

    final ProviderEvaluation<String> ordinary =
        evaluator.evaluate(config, String.class, "ordinary", "default", new MutableContext("u"));
    final FlagEvaluator.EvaluationResult<String> dependent =
        evaluator.evaluateWithTrace(
            config, String.class, "dependent", "default", new MutableContext("u"));

    assertEquals("on-value", ordinary.getValue());
    assertEquals(ErrorCode.PARSE_ERROR, dependent.details.getErrorCode());
    assertNotNull(dependent.details.getFlagMetadata());
    assertEquals(singletonList("dependency:PARSE_ERROR"), evaluations(dependent));
    assertTrue(dependent.dependencyExposures.isEmpty());
  }

  @Test
  void rejectsExplicitNullMaximumDependencyDepthButDefaultsAnEmptyObject() {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("ordinary", stringFlag("ordinary", "on", null, false));
    flags.put("dependency", stringFlag("dependency", "on", null, false));
    flags.put(
        "dependent", stringFlag("dependent", "on", dependencyRule("dependency", "on"), false));
    final FlagEvaluator evaluator = evaluator();

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
    final FlagEvaluator.EvaluationResult<String> explicitNull =
        evaluator.evaluateWithTrace(
            config(flags, new EvaluatorParams(null, true)),
            String.class,
            "dependent",
            "default",
            new MutableContext("u"));
    assertEquals(ErrorCode.PARSE_ERROR, explicitNull.details.getErrorCode());
    assertEquals(singletonList("dependency:PARSE_ERROR"), evaluations(explicitNull));
    assertTrue(explicitNull.dependencyExposures.isEmpty());
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
  void invalidMaximumDepthDoesNotEmitTheSelfDependentRootAsAPrerequisite() {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("root", stringFlag("root", "on", dependencyRule("root", "on"), false));

    final FlagEvaluator.EvaluationResult<String> evaluation =
        evaluator()
            .evaluateWithTrace(
                config(flags, new EvaluatorParams(null, true)),
                String.class,
                "root",
                "default",
                new MutableContext("u"));

    assertEquals(ErrorCode.PARSE_ERROR, evaluation.details.getErrorCode());
    assertTrue(evaluation.dependencyEvaluations.isEmpty());
    assertTrue(evaluation.dependencyExposures.isEmpty());
  }

  @Test
  void preservesWideDependencyExposureOrderWithoutDuplicates() {
    final int dependencyCount = 512;
    final Map<String, Flag> flags = new HashMap<>();
    final List<ConditionConfiguration> conditions = new ArrayList<>();
    for (int i = 0; i < dependencyCount; i++) {
      final String key = "dependency-" + i;
      flags.put(key, stringFlag(key, "on", null, false));
      conditions.add(new ConditionConfiguration(ONE_OF, null, key, singletonList("on")));
    }
    flags.put("root", stringFlag("root", "on", new Rule(conditions), false));

    final FlagEvaluator.EvaluationResult<String> evaluation =
        evaluator()
            .evaluateWithTrace(
                config(flags, null), String.class, "root", "default", new MutableContext("u"));

    assertEquals("on-value", evaluation.details.getValue());
    assertEquals(dependencyCount, evaluation.dependencyEvaluations.size());
    assertEquals(dependencyCount, evaluation.dependencyExposures.size());
    for (int i = 0; i < dependencyCount; i++) {
      assertEquals("dependency-" + i, evaluation.dependencyExposures.get(i).flagKey);
    }
  }

  @Test
  void structurallyReusesExposureTraceForManyParentsOfASharedHub() {
    final int width = 128;
    final Map<String, Flag> flags = new HashMap<>();
    final List<ConditionConfiguration> leafConditions = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      final String leaf = "leaf-" + i;
      flags.put(leaf, stringFlag(leaf, "on", null, true));
      leafConditions.add(new ConditionConfiguration(ONE_OF, null, leaf, singletonList("on")));
    }
    flags.put("hub", stringFlag("hub", "on", new Rule(leafConditions), true));
    final List<ConditionConfiguration> parentConditions = new ArrayList<>();
    for (int i = 0; i < width; i++) {
      final String parent = "parent-" + i;
      flags.put(parent, stringFlag(parent, "on", dependencyRule("hub", "on"), true));
      parentConditions.add(new ConditionConfiguration(ONE_OF, null, parent, singletonList("on")));
    }
    flags.put("root", stringFlag("root", "on", new Rule(parentConditions), false));

    final FlagEvaluator.EvaluationResult<String> evaluation =
        evaluator()
            .evaluateWithTrace(
                config(flags, null), String.class, "root", "default", new MutableContext("u"));

    assertEquals("on-value", evaluation.details.getValue());
    assertEquals(width * 2 + 1, evaluation.dependencyEvaluations.size());
    assertEquals(width * 2 + 1, evaluation.dependencyExposures.size());
    assertEquals("leaf-0", evaluation.dependencyExposures.get(0).flagKey);
    assertEquals("leaf-127", evaluation.dependencyExposures.get(width - 1).flagKey);
    assertEquals("hub", evaluation.dependencyExposures.get(width).flagKey);
    assertEquals("parent-0", evaluation.dependencyExposures.get(width + 1).flagKey);
    assertEquals("parent-127", evaluation.dependencyExposures.get(width * 2).flagKey);
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
        evaluator().evaluate(config, String.class, "root", "default", new MutableContext("u"));

    assertEquals(ErrorCode.PARSE_ERROR, result.getErrorCode());
  }

  @Test
  void supportsNotOneOfAndShortCircuitsBeforeAFatalDependency() {
    final FlagEvaluator evaluator = evaluator();
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

    final FlagEvaluator.EvaluationResult<Integer> evaluation =
        evaluator.evaluateWithTrace(config(flags, null), Integer.class, "root", 0, context);
    final ProviderEvaluation<Integer> result = evaluation.details;

    assertEquals(1, result.getValue());
    assertEquals(singletonList("dependency:on"), evaluations(evaluation));
  }

  @Test
  void emitsMissingAndInvalidPrerequisiteErrors() {
    final Map<String, Flag> missingFlags = new HashMap<>();
    missingFlags.put("root", stringFlag("root", "on", dependencyRule("missing", "on"), false));
    final FlagEvaluator.EvaluationResult<String> missingEvaluation =
        evaluator()
            .evaluateWithTrace(
                config(missingFlags, null, true),
                String.class,
                "root",
                "default",
                new MutableContext("u"));
    assertEquals(ErrorCode.GENERAL, missingEvaluation.details.getErrorCode());
    assertTrue(missingEvaluation.details.getErrorMessage().contains("missing"));
    assertEquals(singletonList("missing:FLAG_NOT_FOUND"), evaluations(missingEvaluation));

    final ServerConfiguration invalidConfig = config(missingFlags, null, true);
    invalidConfig.invalidFlags = singletonMap("missing", "invalid_flag");
    final FlagEvaluator.EvaluationResult<String> invalidEvaluation =
        evaluator()
            .evaluateWithTrace(
                invalidConfig, String.class, "root", "default", new MutableContext("u"));
    assertEquals(ErrorCode.GENERAL, invalidEvaluation.details.getErrorCode());
    assertTrue(invalidEvaluation.details.getErrorMessage().contains("missing"));
    assertEquals(singletonList("missing:PARSE_ERROR"), evaluations(invalidEvaluation));
  }

  @Test
  void rollsBackATransitiveTraceWhenTheRootAllocationHasNoAssignment() {
    final FlagEvaluator evaluator = evaluator();
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

    final FlagEvaluator.EvaluationResult<Integer> evaluation =
        evaluator.evaluateWithTrace(
            config(flags, null), Integer.class, "root", 0, new MutableContext("u"));
    final ProviderEvaluation<Integer> result = evaluation.details;

    assertEquals(1, result.getValue());
    assertEquals(Arrays.asList("leaf:on", "parent:on"), evaluations(evaluation));
    assertTrue(evaluation.dependencyExposures.isEmpty());
  }

  private static FlagEvaluator evaluator() {
    return evaluator(ExperimentationOptOutMode.DISABLED);
  }

  private static FlagEvaluator evaluator(final ExperimentationOptOutMode mode) {
    return new FlagEvaluator(false, (flag, subject, allocation, variant, serialId) -> false, mode);
  }

  private static List<String> evaluations(final FlagEvaluator.EvaluationResult<?> result) {
    final List<String> values = new ArrayList<>();
    for (final FlagEvaluator.DependencyEvaluation evaluation : result.dependencyEvaluations) {
      values.add(
          evaluation.flagKey
              + ":"
              + (evaluation.errorCode == null ? evaluation.variant : evaluation.errorCode.name()));
    }
    return values;
  }

  private static List<String> exposures(final FlagEvaluator.EvaluationResult<?> result) {
    final List<String> values = new ArrayList<>();
    for (final FlagEvaluator.DependencyExposure exposure : result.dependencyExposures) {
      values.add(exposure.flagKey);
    }
    return values;
  }

  private static ServerConfiguration config(
      final Map<String, Flag> flags, final EvaluatorParams params) {
    return config(flags, params, false);
  }

  private static ServerConfiguration config(
      final Map<String, Flag> flags,
      final EvaluatorParams params,
      final boolean observeFullEvaluationData) {
    return new ServerConfiguration("", "", observeFullEvaluationData, null, flags, params);
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
