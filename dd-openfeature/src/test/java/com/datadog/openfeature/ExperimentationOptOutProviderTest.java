package com.datadog.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.openfeature.internal.JsonReading;
import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.exceptions.FatalError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

/** Exercises experimentation opt-out through the production parser and evaluator. */
class ExperimentationOptOutProviderTest {
  private static final String ATTRIBUTE = "dd_experimentation_opt_out_at";
  private static final String CUTOFF = "2026-10-19T12:00:00Z";

  @SuppressWarnings("unchecked")
  static Stream<Arguments> policyCases() throws Exception {
    final byte[] bytes = Files.readAllBytes(fixture("cases.json"));
    final List<Map<String, Object>> cases = (List<Map<String, Object>>) JsonReading.read(bytes);
    assertEquals(90, cases.size());
    final Set<Object> ids = new HashSet<>();
    for (final Map<String, Object> testCase : cases) {
      assertTrue(ids.add(testCase.get("id")), "duplicate policy case");
    }
    return cases.stream().map(testCase -> Arguments.of(testCase.get("id"), testCase));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("policyCases")
  @SuppressWarnings("unchecked")
  void policy(final String id, final Map<String, Object> testCase) throws Exception {
    final Map<String, Object> args = (Map<String, Object>) testCase.get("args");
    final Map<String, Object> expected = (Map<String, Object>) testCase.get("expected");
    final String mode = (String) args.get("mode");
    final ProviderEvaluation<String> result =
        evaluate(
            configuration(
                activation((String) args.get("activation")), args.get("doLog").toString(), false),
            mode,
            (String) args.get("attribute"),
            "target");
    final String error = (String) expected.get("error");
    if (!"NONE".equals(error)) {
      assertEquals(ErrorCode.valueOf(error), result.getErrorCode(), id);
      assertEquals("default", result.getValue(), id);
    } else {
      assertNull(result.getErrorCode(), id);
      final String allocation =
          Boolean.TRUE.equals(expected.get("exclude")) ? "ordinary" : "experiment";
      assertEquals(allocation, result.getValue(), id);
      assertEquals(allocation, result.getFlagMetadata().getString("allocationKey"), id);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"immediate", "future_experiments_only"})
  void missingAndNullDoLogPreserveOrdinaryAllocation(final String mode) throws Exception {
    for (final String doLog : new String[] {null, "null", "false"}) {
      final ProviderEvaluation<String> result =
          evaluate(configuration(null, doLog, false), mode, "valid", "target");
      assertNull(result.getErrorCode());
      assertEquals("experiment", result.getValue());
      assertEquals("experiment", result.getFlagMetadata().getString("allocationKey"));
    }
  }

  @Test
  void unreachableInvalidActivationFailsOnlyOptedOutFutureMode() throws Exception {
    final String json = configuration("\"bad\"", "true", true);
    for (final String mode : new String[] {"disabled", "immediate", "future_experiments_only"}) {
      for (final String attribute : new String[] {"absent", "valid"}) {
        final ProviderEvaluation<String> result = evaluate(json, mode, attribute, "target");
        if ("future_experiments_only".equals(mode) && "valid".equals(attribute)) {
          assertEquals(ErrorCode.PARSE_ERROR, result.getErrorCode());
          assertEquals("default", result.getValue());
        } else {
          assertNull(result.getErrorCode());
          assertEquals("ordinary", result.getValue());
        }
        final ProviderEvaluation<String> neighbor = evaluate(json, mode, attribute, "neighbor");
        assertNull(neighbor.getErrorCode());
        assertEquals("ordinary", neighbor.getValue());
      }
    }
  }

  @Test
  void invalidSubjectPrecedesMissingFlagButNotReadiness() throws Exception {
    final ProviderEvaluation<String> missing =
        evaluate(
            configuration(null, "true", false), "future_experiments_only", "invalid", "missing");
    assertEquals(ErrorCode.INVALID_CONTEXT, missing.getErrorCode());
    final ProviderEvaluation<String> notReady =
        evaluate(null, "future_experiments_only", "invalid", "missing");
    assertEquals(ErrorCode.PROVIDER_NOT_READY, notReady.getErrorCode());
  }

  @TableTest({
    "Scenario                                 | deprecatedAlias | mode                    | configured              | expectedValue",
    "explicit False DISABLED                  | false           | DISABLED                | unsupported             | experiment   ",
    "configured False DISABLED                | false           | DEFAULT                 | disabled                | experiment   ",
    "explicit False IMMEDIATE                 | false           | IMMEDIATE               | unsupported             | ordinary     ",
    "configured False IMMEDIATE               | false           | DEFAULT                 | immediate               | ordinary     ",
    "explicit False FUTURE_EXPERIMENTS_ONLY   | false           | FUTURE_EXPERIMENTS_ONLY | unsupported             | ordinary     ",
    "configured False FUTURE_EXPERIMENTS_ONLY | false           | DEFAULT                 | future_experiments_only | ordinary     ",
    "explicit True DISABLED                   | true            | DISABLED                | unsupported             | experiment   ",
    "configured True DISABLED                 | true            | DEFAULT                 | disabled                | experiment   ",
    "explicit True IMMEDIATE                  | true            | IMMEDIATE               | unsupported             | ordinary     ",
    "configured True IMMEDIATE                | true            | DEFAULT                 | immediate               | ordinary     ",
    "explicit True FUTURE_EXPERIMENTS_ONLY    | true            | FUTURE_EXPERIMENTS_ONLY | unsupported             | ordinary     ",
    "configured True FUTURE_EXPERIMENTS_ONLY  | true            | DEFAULT                 | future_experiments_only | ordinary     "
  })
  @SuppressWarnings("deprecation")
  void publicProvidersApplyOptOutAfterHttpConfiguration(
      final boolean deprecatedAlias,
      final ExperimentationOptOutMode mode,
      final String configured,
      final String expectedValue)
      throws Exception {
    try (final LocalHttpServer server = new LocalHttpServer()) {
      server.otherwise(
          200,
          "{\"data\":{\"type\":\"universal-flag-configuration\",\"attributes\":"
              + configuration(activation("equal"), "true", false)
              + "}}");
      final Map<String, String> previousProperties = new HashMap<>();
      final Map<String, String> properties =
          Map.of(
              "dd.feature.flags.enabled", "true",
              "dd.feature.flags.configuration.source", "agentless",
              "dd.feature.flags.configuration.source.agentless.base.url",
                  server.uri("/").toString(),
              "dd.feature.flags.experimentation.opt.out.mode", configured,
              "dd.api.key", "");
      properties.forEach(
          (key, value) -> previousProperties.put(key, System.setProperty(key, value)));
      try {
        final Provider.Options options =
            deprecatedAlias
                ? new datadog.trace.api.openfeature.Provider.Options()
                : new Provider.Options();
        options.initTimeout(5, SECONDS).experimentationOptOutMode(mode);
        final Provider provider =
            deprecatedAlias
                ? new datadog.trace.api.openfeature.Provider(
                    (datadog.trace.api.openfeature.Provider.Options) options)
                : new Provider(options);
        // The policy is resolved at construction, before initialization or later option changes.
        options.experimentationOptOutMode(ExperimentationOptOutMode.DEFAULT);
        System.setProperty(
            "dd.feature.flags.experimentation.opt.out.mode", "unsupported-after-construction");
        final MutableContext context = new MutableContext("subject").add(ATTRIBUTE, CUTOFF);
        try {
          provider.initialize(context);
          final ProviderEvaluation<String> result =
              provider.getStringEvaluation("target", "default", context);
          assertNull(result.getErrorCode());
          assertEquals(expectedValue, result.getValue());
          assertEquals(expectedValue, result.getFlagMetadata().getString("allocationKey"));
          assertTrue(server.requests().size() > 0);
        } finally {
          provider.shutdown();
        }
      } finally {
        previousProperties.forEach(
            (key, value) -> {
              if (value == null) {
                System.clearProperty(key);
              } else {
                System.setProperty(key, value);
              }
            });
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @SuppressWarnings("deprecation")
  void disabledProductDoesNotValidateAnUnusedMode(final boolean alias) {
    final String enabledKey = "dd.feature.flags.enabled";
    final String modeKey = "dd.feature.flags.experimentation.opt.out.mode";
    final String previousEnabled = System.setProperty(enabledKey, "false");
    final String previousMode = System.setProperty(modeKey, "unsupported");
    try {
      final Provider provider =
          alias ? new datadog.trace.api.openfeature.Provider() : new Provider();
      try {
        // Disabling Feature Flags keeps the existing initialization failure, not a mode error.
        final FatalError error =
            assertThrows(
                FatalError.class, () -> provider.initialize(new MutableContext("subject")));
        assertEquals(
            "Failed to initialize Datadog Feature Flags provider: Feature Flags is disabled",
            error.getMessage());
      } finally {
        provider.shutdown();
      }
    } finally {
      restoreProperty(enabledKey, previousEnabled);
      restoreProperty(modeKey, previousMode);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"IMMEDIATE", "FUTURE_EXPERIMENTS_ONLY"})
  void excludedAllocationDoesNotEvaluateSubjectDependentNumericRules(final String mode)
      throws Exception {
    final DDEvaluator evaluator =
        evaluator(withRule(configuration(activation("equal"), "true", false), "GT", "10"), mode);
    final MutableContext context = new MutableContext("subject").add("attribute", "not-a-number");
    assertEquals(
        ErrorCode.TYPE_MISMATCH,
        evaluator.evaluate(String.class, "target", "default", context).getErrorCode());
    context.add(ATTRIBUTE, CUTOFF);
    final ProviderEvaluation<String> result =
        evaluator.evaluate(String.class, "target", "default", context);
    assertNull(result.getErrorCode());
    assertEquals("ordinary", result.getValue());
  }

  @Test
  void configurationRefreshReplacesActivationAndRecoversFromInvalidMetadata() throws Exception {
    final DDEvaluator evaluator = evaluator(null, "FUTURE_EXPERIMENTS_ONLY");
    final MutableContext context = new MutableContext("subject").add(ATTRIBUTE, CUTOFF);
    for (final String activation : new String[] {"before", "equal", "invalid", "before"}) {
      evaluator.accept(
          UniversalFlagConfigParser.parse(
              configuration(activation(activation), "true", false).getBytes(UTF_8)));
      final ProviderEvaluation<String> result =
          evaluator.evaluate(String.class, "target", "default", context);
      if ("invalid".equals(activation)) {
        assertEquals(ErrorCode.PARSE_ERROR, result.getErrorCode());
        assertEquals("default", result.getValue());
      } else {
        assertNull(result.getErrorCode());
        assertEquals("before".equals(activation) ? "experiment" : "ordinary", result.getValue());
      }
      assertEquals(
          "ordinary", evaluator.evaluate(String.class, "neighbor", "default", context).getValue());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void invalidSubjectRetainsEvaluationDataConsent(final boolean consent) throws Exception {
    final String json =
        configuration(null, "true", false)
            .replaceFirst("\\{", "{\"observeFullEvaluationData\":" + consent + ",");
    final ProviderEvaluation<String> result = evaluate(json, "immediate", "invalid", "target");
    assertEquals(ErrorCode.INVALID_CONTEXT, result.getErrorCode());
    assertEquals("default", result.getValue());
    assertEquals(consent, result.getFlagMetadata().getBoolean("observe_full_evaluation_data"));
  }

  @Test
  void missingAllocationsRetainGeneralErrorInFutureMode() throws Exception {
    final ProviderEvaluation<String> result =
        evaluate(
            "{\"flags\":{\"target\":{\"enabled\":true,\"variationType\":\"STRING\"}}}",
            "future_experiments_only",
            "valid",
            "target");
    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals("default", result.getValue());
  }

  @ParameterizedTest
  @ValueSource(strings = {"keySet", "getValue"})
  void contextImplementationFailureIsNotAnInvalidTimestamp(final String method) throws Exception {
    final EvaluationContext context = mock(EvaluationContext.class);
    when(context.keySet()).thenReturn(Set.of(ATTRIBUTE));
    if ("keySet".equals(method)) {
      when(context.keySet()).thenThrow(new IllegalArgumentException("context failure"));
    } else {
      when(context.getValue(ATTRIBUTE)).thenThrow(new IllegalArgumentException("context failure"));
    }
    final ProviderEvaluation<String> result =
        evaluator(configuration(null, "true", false), "IMMEDIATE")
            .evaluate(String.class, "target", "default", context);
    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals("default", result.getValue());
  }

  @TableTest({
    "operator    | position   ",
    "MATCHES     | excluded   ",
    "MATCHES     | unreachable",
    "MATCHES     | expired    ",
    "MATCHES     | disabled   ",
    "NOT_MATCHES | excluded   ",
    "NOT_MATCHES | unreachable",
    "NOT_MATCHES | expired    ",
    "NOT_MATCHES | disabled   "
  })
  void malformedRegexFailsOnlyItsFlagRegardlessOfSelection(
      final String operator, final String position) throws Exception {
    String json =
        withRule(
            configuration(activation("equal"), "true", "unreachable".equals(position)),
            operator,
            "\"*@datadoghq.com\"");
    if ("expired".equals(position)) {
      json =
          json.replace(
              "\"key\":\"experiment\",\"rules\"",
              "\"key\":\"experiment\",\"endAt\":\"2000-01-01T00:00:00Z\",\"rules\"");
    }
    if ("disabled".equals(position)) {
      json = json.replaceFirst("\"enabled\":true", "\"enabled\":false");
    }
    for (final String mode : new String[] {"DISABLED", "IMMEDIATE", "FUTURE_EXPERIMENTS_ONLY"}) {
      final DDEvaluator evaluator = evaluator(json, mode);
      for (final boolean optedOut : new boolean[] {false, true}) {
        for (final boolean hasAttribute : new boolean[] {false, true}) {
          final MutableContext context = new MutableContext("subject");
          if (optedOut) context.add(ATTRIBUTE, CUTOFF);
          if (hasAttribute) context.add("attribute", "user@example.com");
          final ProviderEvaluation<String> result =
              evaluator.evaluate(String.class, "target", "default", context);
          assertEquals(ErrorCode.PARSE_ERROR, result.getErrorCode());
          assertEquals("default", result.getValue());
          final ProviderEvaluation<String> neighbor =
              evaluator.evaluate(String.class, "neighbor", "default", context);
          assertNull(neighbor.getErrorCode());
          assertEquals("ordinary", neighbor.getValue());
        }
      }
      assertEquals(
          "DISABLED".equals(mode) ? ErrorCode.PARSE_ERROR : ErrorCode.INVALID_CONTEXT,
          evaluator
              .evaluate(
                  String.class,
                  "target",
                  "default",
                  new MutableContext("subject").add(ATTRIBUTE, "bad"))
              .getErrorCode());
    }
  }

  @TableTest({
    "type    | value         | valid",
    "INTEGER | 42            | true ",
    "INTEGER | '\"42\"'      | true ",
    "INTEGER | '\"invalid\"' | false",
    "INTEGER | true          | false",
    "NUMERIC | 4.2           | true ",
    "NUMERIC | '\"4.2\"'     | true ",
    "NUMERIC | '\"invalid\"' | false",
    "NUMERIC | '{}'          | false"
  })
  void numericVariantValidityIsIndependentOfOptOut(
      final String type, final String value, final boolean valid) throws Exception {
    // Only the experiment variant in the target flag changes; the neighboring flag remains valid.
    final String original = configuration(activation("equal"), "true", false);
    final int neighbor = original.indexOf(",\"neighbor\":");
    final String target =
        original
            .substring(0, neighbor)
            .replace("\"variationType\":\"STRING\"", "\"variationType\":\"" + type + "\"")
            .replace("\"value\":\"experiment\"", "\"value\":" + value)
            .replace("\"value\":\"ordinary\"", "\"value\":1");
    for (final String mode : new String[] {"IMMEDIATE", "FUTURE_EXPERIMENTS_ONLY"}) {
      final DDEvaluator evaluator = evaluator(target + original.substring(neighbor), mode);
      final MutableContext context = new MutableContext("subject").add(ATTRIBUTE, CUTOFF);
      final ProviderEvaluation<?> result =
          "INTEGER".equals(type)
              ? evaluator.evaluate(Integer.class, "target", 0, context)
              : evaluator.evaluate(Double.class, "target", 0.0, context);
      assertEquals(valid ? null : ErrorCode.PARSE_ERROR, result.getErrorCode());
      assertEquals(valid ? 1.0 : 0.0, ((Number) result.getValue()).doubleValue());
      assertEquals(
          "ordinary", evaluator.evaluate(String.class, "neighbor", "default", context).getValue());
    }
  }

  private static DDEvaluator evaluator(final String json, final String mode) throws Exception {
    final DDEvaluator evaluator =
        new DDEvaluator(
            () -> {}, AgentBridge.NONE, TestSettings.of(), ExperimentationOptOutMode.valueOf(mode));
    if (json != null) evaluator.accept(UniversalFlagConfigParser.parse(json.getBytes(UTF_8)));
    return evaluator;
  }

  private static String withRule(final String json, final String operator, final String operand) {
    return json.replace(
        "\"key\":\"experiment\",\"doLog\"",
        "\"key\":\"experiment\",\"rules\":[{\"conditions\":[{\"attribute\":\"attribute\",\"operator\":\""
            + operator
            + "\",\"value\":"
            + operand
            + "}]}],\"doLog\"");
  }

  private static void restoreProperty(final String key, final String previous) {
    if (previous == null) System.clearProperty(key);
    else System.setProperty(key, previous);
  }

  private static ProviderEvaluation<String> evaluate(
      final String json, final String configuredMode, final String attribute, final String flag)
      throws Exception {
    final ExperimentationOptOutMode mode =
        ExperimentationOptOutMode.valueOf(configuredMode.toUpperCase(Locale.ROOT));
    final DDEvaluator evaluator =
        new DDEvaluator(() -> {}, AgentBridge.NONE, TestSettings.of(), mode);
    if (json != null) {
      evaluator.accept(UniversalFlagConfigParser.parse(json.getBytes(UTF_8)));
    }
    final MutableContext context = new MutableContext("subject");
    if (!"absent".equals(attribute)) {
      context.add(ATTRIBUTE, "valid".equals(attribute) ? CUTOFF : "bad");
    }
    return evaluator.evaluate(String.class, flag, "default", context);
  }

  private static String activation(final String state) {
    switch (state) {
      case "missing":
        return null;
      case "invalid":
        return "\"bad\"";
      case "before":
        return "\"2026-10-19T11:59:59.999999999Z\"";
      case "equal":
        return "\"2026-10-19T12:00:00Z\"";
      case "after":
        return "\"2026-10-19T12:00:00.000000001Z\"";
      default:
        throw new IllegalArgumentException(state);
    }
  }

  private static String configuration(
      final String activation, final String doLog, final boolean unreachable) {
    final String ordinary =
        "{\"key\":\"ordinary\",\"doLog\":false,\"splits\":[{\"variationKey\":\"ordinary\",\"shards\":[]}]}";
    final String experiment =
        "{\"key\":\"experiment\""
            + (doLog == null ? "" : ",\"doLog\":" + doLog)
            + (activation == null ? "" : ",\"randomizationActivatedAt\":" + activation)
            + ",\"splits\":[{\"variationKey\":\"experiment\",\"shards\":[]}]}";
    final String prefix =
        "{\"enabled\":true,\"variationType\":\"STRING\",\"variations\":{\"experiment\":{\"key\":\"experiment\",\"value\":\"experiment\"},\"ordinary\":{\"key\":\"ordinary\",\"value\":\"ordinary\"}},\"allocations\":[";
    return "{\"flags\":{\"target\":"
        + prefix
        + (unreachable ? ordinary + "," + experiment : experiment + "," + ordinary)
        + "]},\"neighbor\":"
        + prefix
        + ordinary
        + "]}}}";
  }

  private static Path fixture(final String name) {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path candidate =
          directory
              .resolve("dd-smoke-tests/openfeature/src/test/resources/experimentation-opt-out")
              .resolve(name);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Missing experimentation opt-out fixture " + name);
  }
}
