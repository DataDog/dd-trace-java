package com.datadog.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.JsonReading;
import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
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

/** Runs pinned Lean policy cases through the production parser and evaluator. */
class ExperimentationOptOutConformanceTest {
  private static final String ATTRIBUTE = "dd_experimentation_opt_out_at";
  private static final String CUTOFF = "2026-10-19T12:00:00Z";

  @SuppressWarnings("unchecked")
  static Stream<Arguments> policyCases() throws Exception {
    final byte[] bytes = Files.readAllBytes(fixture("java-policy-v1-cases.json"));
    assertEquals("289a5d2dca4a7e5dd1e0428b4c56e9c1660668375aab67b71c91b27b9391f4e2", sha256(bytes));
    final List<Map<String, Object>> cases = (List<Map<String, Object>>) JsonReading.read(bytes);
    assertEquals(90, cases.size());
    final byte[] profileBytes = Files.readAllBytes(fixture("java-policy-v1.json"));
    assertEquals(
        "ad281f827a0e57a0aa4528157d5ab4a967bc0233cbae5d563f7408fe9f4017ff", sha256(profileBytes));
    final Map<String, Object> profile = (Map<String, Object>) JsonReading.read(profileBytes);
    final Set<Object> ids = new HashSet<>();
    for (final Map<String, Object> testCase : cases) {
      assertTrue(ids.add(testCase.get("id")), "duplicate policy case");
    }
    assertEquals(new HashSet<>((List<?>) profile.get("included_ids")), ids);
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
    "Scenario        | deprecatedAlias | mode                    | expectedValue",
    "SDK disabled    | false           | DISABLED                | experiment   ",
    "SDK immediate   | false           | IMMEDIATE               | ordinary     ",
    "SDK future      | false           | FUTURE_EXPERIMENTS_ONLY | ordinary     ",
    "alias disabled  | true            | DISABLED                | experiment   ",
    "alias immediate | true            | IMMEDIATE               | ordinary     ",
    "alias future    | true            | FUTURE_EXPERIMENTS_ONLY | ordinary     "
  })
  @SuppressWarnings("deprecation")
  void publicProvidersApplyOptOutAfterHttpConfiguration(
      final boolean deprecatedAlias,
      final ExperimentationOptOutMode mode,
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
              "dd.feature.flags.experimentation.opt.out.mode", "invalid-overridden-mode",
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
    throw new IllegalStateException("Missing pinned experimentation opt-out fixture " + name);
  }

  private static String sha256(final byte[] bytes) throws Exception {
    final StringBuilder hash = new StringBuilder();
    for (final byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
      hash.append(String.format(Locale.ROOT, "%02x", value & 0xff));
    }
    return hash.toString();
  }
}
