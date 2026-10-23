package com.datadog.openfeature;

import static java.util.Collections.emptyMap;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.JsonReading;
import com.datadog.openfeature.internal.bridge.AgentBridge;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.evaluation.ExperimentationOptOut;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Evaluates the cross-SDK canonical fixtures from the {@code ffe-system-test-data} submodule,
 * parsed with the production UFC parser.
 */
class CanonicalFixtureTest {
  private static final String CANONICAL_FIXTURE_PATH =
      "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data";

  @Test
  void canonicalFixturesArePresent() throws IOException {
    assertThat(canonicalTestCases().size(), greaterThan(0));
  }

  @MethodSource("canonicalTestCases")
  @ParameterizedTest(name = "{0}")
  void evaluateCanonicalFixture(final FixtureCase testCase) throws IOException {
    evaluateCanonicalFixture(testCase, ExperimentationOptOutMode.DISABLED);
  }

  @MethodSource("immediateOptOutTestCases")
  @ParameterizedTest(name = "immediate: {0}")
  void evaluateImmediateOptOutFixture(final FixtureCase testCase) throws IOException {
    evaluateCanonicalFixture(testCase, ExperimentationOptOutMode.IMMEDIATE);
  }

  @MethodSource("futureOptOutTestCases")
  @ParameterizedTest(name = "future: {0}")
  void evaluateFutureOptOutFixture(final FixtureCase testCase) throws IOException {
    evaluateCanonicalFixture(testCase, ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY);
  }

  static List<FixtureCase> futureOptOutTestCases() throws IOException {
    return optOutTestCases("experimentationOptOutFutureExperimentsOnly", 44);
  }

  static List<FixtureCase> immediateOptOutTestCases() throws IOException {
    return optOutTestCases("experimentationOptOutImmediate", 28);
  }

  @SuppressWarnings("unchecked")
  private static List<FixtureCase> optOutTestCases(final String capability, final int expectedCount)
      throws IOException {
    final List<FixtureCase> cases = new ArrayList<>();
    for (final FixtureCase testCase : canonicalTestCases()) {
      final Map<String, Object> capabilities =
          (Map<String, Object>) testCase.json.get("capabilities");
      if (capabilities != null && capabilities.containsKey(capability)) {
        final Map<String, Object> json = new java.util.LinkedHashMap<>(testCase.json);
        json.put("result", capabilities.get(capability));
        cases.add(new FixtureCase(testCase.fileName, testCase.index, json));
      }
    }
    assertEquals(expectedCount, cases.size(), "The opt-out capability fixtures must execute");
    return cases;
  }

  private void evaluateCanonicalFixture(
      final FixtureCase testCase, final ExperimentationOptOutMode mode) throws IOException {
    final DDEvaluator evaluator =
        new DDEvaluator(mock(Runnable.class), AgentBridge.NONE, TestSettings.of(), mode);
    evaluator.accept(
        UniversalFlagConfigParser.parse(
            Files.readAllBytes(fixtureRoot().resolve("ufc-config.json"))));

    final Class<?> targetType = targetType(testCase.variationType);
    final Object defaultValue = fixtureValue(targetType, testCase.defaultValue);
    final Object expectedValue = fixtureValue(targetType, testCase.resultValue);
    final Provider provider =
        new Provider(new Provider.Options().experimentationOptOutMode(mode), evaluator, false);
    final ProviderEvaluation<?> details;
    try {
      details = evaluate(provider, targetType, testCase.flag, defaultValue, context(testCase));
    } finally {
      provider.shutdown();
    }

    assertThat(details.getValue(), equalTo(expectedValue));
    assertThat(details.getReason(), equalTo(testCase.resultReason));
    if (testCase.resultVariant != null) {
      assertThat(details.getVariant(), equalTo(testCase.resultVariant));
    }
    if (testCase.resultErrorCode != null) {
      assertThat(details.getErrorCode(), equalTo(ErrorCode.valueOf(testCase.resultErrorCode)));
      if ("INVALID_CONTEXT".equals(testCase.resultErrorCode)) {
        assertEquals(ExperimentationOptOut.INVALID_TIMESTAMP, details.getErrorMessage());
      }
    } else if (!"ERROR".equals(testCase.resultReason)) {
      assertNull(details.getErrorCode());
    }
    if (testCase.resultAllocationKey != null) {
      assertThat(
          details.getFlagMetadata().getString("allocationKey"),
          equalTo(testCase.resultAllocationKey));
    }
  }

  private static Object fixtureValue(final Class<?> type, final Object value) {
    if (value == null) {
      return null;
    }
    if (type == Value.class) {
      return Value.objectToValue(value);
    }
    if (type == Integer.class) {
      return ((Number) value).intValue();
    }
    if (type == Double.class) {
      return ((Number) value).doubleValue();
    }
    return value;
  }

  private static ProviderEvaluation<?> evaluate(
      final Provider provider,
      final Class<?> targetType,
      final String flag,
      final Object defaultValue,
      final EvaluationContext context) {
    if (targetType == Boolean.class) {
      return provider.getBooleanEvaluation(flag, (Boolean) defaultValue, context);
    }
    if (targetType == Integer.class) {
      return provider.getIntegerEvaluation(flag, (Integer) defaultValue, context);
    }
    if (targetType == Double.class) {
      return provider.getDoubleEvaluation(flag, (Double) defaultValue, context);
    }
    if (targetType == String.class) {
      return provider.getStringEvaluation(flag, (String) defaultValue, context);
    }
    return provider.getObjectEvaluation(flag, (Value) defaultValue, context);
  }

  @SuppressWarnings("unchecked")
  static List<FixtureCase> canonicalTestCases() throws IOException {
    final Path evaluationCases = fixtureRoot().resolve("evaluation-cases");
    final List<FixtureCase> result = new ArrayList<>();
    try (Stream<Path> paths = Files.list(evaluationCases)) {
      final List<Path> files =
          paths
              .filter(path -> path.getFileName().toString().endsWith(".json"))
              .sorted()
              .collect(Collectors.toList());
      for (final Path file : files) {
        final List<Object> testCases = (List<Object>) JsonReading.read(Files.readAllBytes(file));
        for (int index = 0; index < testCases.size(); index++) {
          result.add(
              new FixtureCase(
                  file.getFileName().toString(),
                  index,
                  (Map<String, Object>) testCases.get(index)));
        }
      }
    }
    assertThat(result.size(), greaterThan(0));
    return result;
  }

  private static Path fixtureRoot() {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path candidate = directory.resolve(CANONICAL_FIXTURE_PATH);
      if (Files.exists(candidate.resolve("ufc-config.json"))
          && Files.isDirectory(candidate.resolve("evaluation-cases"))) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Unable to find canonical FFE fixtures");
  }

  private static EvaluationContext context(final FixtureCase testCase) {
    final MutableContext context =
        new MutableContext(Value.objectToValue(testCase.attributes).asStructure().asMap());
    if (testCase.targetingKey != null) {
      context.setTargetingKey(testCase.targetingKey);
    }
    return context;
  }

  private static Class<?> targetType(final String variationType) {
    switch (variationType) {
      case "BOOLEAN":
        return Boolean.class;
      case "INTEGER":
        return Integer.class;
      case "NUMERIC":
        return Double.class;
      case "STRING":
        return String.class;
      case "JSON":
        return Value.class;
      default:
        throw new IllegalArgumentException("Unsupported variationType: " + variationType);
    }
  }

  /** Normalizes the integral numbers read as Long to Double, as the UFC parser does. */
  private static Object normalize(final Object value) {
    if (value instanceof Long) {
      return ((Long) value).doubleValue();
    }
    if (value instanceof Map) {
      final Map<String, Object> map = new java.util.LinkedHashMap<>();
      ((Map<?, ?>) value).forEach((k, v) -> map.put((String) k, normalize(v)));
      return map;
    }
    if (value instanceof List) {
      final List<Object> list = new ArrayList<>();
      ((List<?>) value).forEach(v -> list.add(normalize(v)));
      return list;
    }
    return value;
  }

  static final class FixtureCase {
    final Map<String, Object> json;
    final String fileName;
    final int index;
    final Map<String, Object> attributes;
    final Object defaultValue;
    final String flag;
    final String targetingKey;
    final String variationType;
    final Object resultValue;
    final String resultReason;
    final String resultErrorCode;
    final String resultVariant;
    final String resultAllocationKey;

    @SuppressWarnings("unchecked")
    FixtureCase(final String fileName, final int index, final Map<String, Object> json) {
      this.json = json;
      this.fileName = fileName;
      this.index = index;
      final Object attributes = normalize(json.get("attributes"));
      this.attributes = attributes == null ? emptyMap() : (Map<String, Object>) attributes;
      this.defaultValue = normalize(json.get("defaultValue"));
      this.flag = (String) json.get("flag");
      this.targetingKey = (String) json.get("targetingKey");
      this.variationType = (String) json.get("variationType");
      final Map<String, Object> result = (Map<String, Object>) json.get("result");
      this.resultValue = normalize(result.get("value"));
      this.resultReason = (String) result.get("reason");
      this.resultErrorCode = (String) result.get("errorCode");
      this.resultVariant = (String) result.get("variant");
      final Map<String, Object> metadata = (Map<String, Object>) result.get("flagMetadata");
      final Object allocationKey = metadata == null ? null : metadata.get("allocationKey");
      this.resultAllocationKey = allocationKey == null ? null : String.valueOf(allocationKey);
    }

    @Override
    public String toString() {
      return this.fileName + "[" + this.index + "] flag=" + this.flag;
    }
  }
}
