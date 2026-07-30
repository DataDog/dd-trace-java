package datadog.openfeature.internal.core;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.openfeature.internal.core.EvaluationResult.Error;
import datadog.openfeature.internal.core.EvaluationResult.Reason;
import datadog.openfeature.internal.core.FlagEvaluator.ValueKind;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Environment;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FlagEvaluatorTest {

  private static final String CANONICAL_FIXTURE_PATH =
      "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data";
  private static final Type FIXTURE_LIST_TYPE =
      Types.newParameterizedType(List.class, FixtureCase.class);
  private static final JsonAdapter<List<FixtureCase>> FIXTURE_LIST_ADAPTER =
      new Moshi.Builder().build().adapter(FIXTURE_LIST_TYPE);
  private static final UfcParser UFC_PARSER = new UfcParser();

  private final FlagEvaluator evaluator = new FlagEvaluator();

  @ParameterizedTest(name = "{0}")
  @MethodSource("canonicalTestCases")
  void evaluatesCanonicalFixture(final FixtureCase testCase) throws IOException {
    final ValueKind valueKind = valueKind(testCase.variationType);
    final Object defaultValue = FlagEvaluator.mapValue(valueKind, testCase.defaultValue);
    final EvaluationResult result =
        evaluator.evaluate(
            loadCanonicalConfiguration(),
            valueKind,
            testCase.flag,
            defaultValue,
            context(testCase));

    assertEquals(FlagEvaluator.mapValue(valueKind, testCase.result.value), result.value);
    assertEquals(Reason.valueOf(testCase.result.reason), result.reason);
    if (testCase.result.errorCode != null) {
      assertEquals(Error.valueOf(testCase.result.errorCode), result.error);
    }
  }

  @Test
  void canonicalFixturesArePresent() throws IOException {
    assertFalse(canonicalTestCases().isEmpty());
  }

  @Test
  void evaluatesMetadataExcludedFromCanonicalFixtures() {
    final EvaluationResult result =
        evaluate(staticFlag(ValueType.STRING, "hello"), ValueKind.STRING, context("subject"));

    assertEquals("hello", result.value);
    assertEquals("on", result.variant);
    assertEquals(Reason.STATIC, result.reason);
    assertEquals("allocation", result.allocationKey);
    assertEquals(7, result.splitSerialId);
    assertEquals(true, result.doLog);
  }

  @Test
  void reportsErrorsOutsideCanonicalFixtureContract() {
    assertError(null, ValueKind.STRING, context("id"), Error.PROVIDER_NOT_READY);
    assertError(
        snapshot(staticFlag(ValueType.STRING, "value")),
        ValueKind.STRING,
        null,
        Error.INVALID_CONTEXT);
    assertError(
        snapshot(
            new Flag("flag", true, ValueType.STRING, map("on", new Variant("on", "value")), null)),
        ValueKind.STRING,
        context("id"),
        Error.GENERAL);
    assertError(
        snapshot(staticFlag(ValueType.STRING, "value")),
        ValueKind.BOOLEAN,
        context("id"),
        Error.TYPE_MISMATCH);
    assertError(
        snapshot(
            flag(ValueType.STRING, map("on", new Variant("on", "value")), list(split("missing")))),
        ValueKind.STRING,
        context("id"),
        Error.GENERAL);
    assertError(
        snapshot(staticFlag(ValueType.INTEGER, "not-a-number")),
        ValueKind.INTEGER,
        context("id"),
        Error.PARSE_ERROR);
  }

  @Test
  void reportsMalformedRuleErrorsOutsideCanonicalFixtureContract() {
    final Flag invalidRegex = targetedFlag(condition(ConditionOperator.MATCHES, "value", "["));
    assertEquals(
        Error.PARSE_ERROR,
        evaluate(invalidRegex, ValueKind.STRING, new EvaluationContext("id", map("value", "text")))
            .error);

    final Flag invalidNumber = targetedFlag(condition(ConditionOperator.GT, "value", "number"));
    assertEquals(
        Error.TYPE_MISMATCH,
        evaluate(invalidNumber, ValueKind.STRING, new EvaluationContext("id", map("value", 2)))
            .error);
  }

  private static ServerConfiguration loadCanonicalConfiguration() throws IOException {
    return UFC_PARSER.parse(Files.readAllBytes(fixtureRoot().resolve("ufc-config.json")));
  }

  private static List<FixtureCase> canonicalTestCases() throws IOException {
    final Path evaluationCases = fixtureRoot().resolve("evaluation-cases");
    final List<FixtureCase> result = new ArrayList<>();

    try (final Stream<Path> paths = Files.list(evaluationCases)) {
      final List<Path> files =
          paths
              .filter(path -> path.getFileName().toString().endsWith(".json"))
              .sorted((left, right) -> left.getFileName().compareTo(right.getFileName()))
              .collect(Collectors.toList());
      for (final Path file : files) {
        final List<FixtureCase> testCases = FIXTURE_LIST_ADAPTER.fromJson(read(file));
        if (testCases == null) {
          throw new JsonDataException("Fixture file did not contain an array: " + file);
        }
        for (int index = 0; index < testCases.size(); index++) {
          final FixtureCase testCase = testCases.get(index);
          testCase.fileName = file.getFileName().toString();
          testCase.index = index;
          result.add(testCase);
        }
      }
    }

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

  private static String read(final Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  private static ValueKind valueKind(final String variationType) {
    switch (variationType) {
      case "BOOLEAN":
        return ValueKind.BOOLEAN;
      case "INTEGER":
        return ValueKind.INTEGER;
      case "NUMERIC":
        return ValueKind.DOUBLE;
      case "STRING":
        return ValueKind.STRING;
      case "JSON":
        return ValueKind.OBJECT;
      default:
        throw new IllegalArgumentException("Unsupported variationType: " + variationType);
    }
  }

  private static EvaluationContext context(final FixtureCase testCase) {
    return new EvaluationContext(
        testCase.targetingKey, testCase.attributes == null ? emptyMap() : testCase.attributes);
  }

  private EvaluationResult evaluate(
      final Flag flag, final ValueKind kind, final EvaluationContext context) {
    return evaluator.evaluate(snapshot(flag), kind, "flag", "default", context);
  }

  private void assertError(
      final ServerConfiguration snapshot,
      final ValueKind kind,
      final EvaluationContext context,
      final Error error) {
    assertEquals(error, evaluator.evaluate(snapshot, kind, "flag", "default", context).error);
  }

  private static ServerConfiguration snapshot(final Flag flag) {
    return new ServerConfiguration(null, "SERVER", new Environment("test"), map("flag", flag));
  }

  private static EvaluationContext context(final String targetingKey) {
    return new EvaluationContext(targetingKey, emptyMap());
  }

  private static Flag staticFlag(final ValueType type, final Object value) {
    return flag(type, map("on", new Variant("on", value)), list(split("on")));
  }

  private static Flag targetedFlag(final ConditionConfiguration condition) {
    return new Flag(
        "flag",
        true,
        ValueType.STRING,
        map("on", new Variant("on", "value")),
        list(
            new Allocation(
                "allocation",
                list(new Rule(list(condition))),
                null,
                null,
                list(split("on")),
                false)));
  }

  private static Flag flag(
      final ValueType type, final Map<String, Variant> variants, final List<Split> splits) {
    return new Flag(
        "flag",
        true,
        type,
        variants,
        list(new Allocation("allocation", emptyList(), null, null, splits, true)));
  }

  private static Split split(final String variationKey) {
    return new Split(emptyList(), variationKey, emptyMap(), 7);
  }

  private static ConditionConfiguration condition(
      final ConditionOperator operator, final String attribute, final Object value) {
    return new ConditionConfiguration(operator, attribute, value);
  }

  @SafeVarargs
  private static <T> List<T> list(final T... values) {
    final List<T> result = new ArrayList<>(values.length);
    for (final T value : values) {
      result.add(value);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Map<K, V> map(final Object... keyValues) {
    final Map<K, V> result = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      result.put((K) keyValues[index], (V) keyValues[index + 1]);
    }
    return result;
  }

  private static final class FixtureCase {
    Map<String, Object> attributes = emptyMap();
    Object defaultValue;
    String flag;
    FixtureResult result;
    String targetingKey;
    String variationType;
    transient String fileName;
    transient int index;

    @Override
    public String toString() {
      return fileName + "[" + index + "] flag=" + flag;
    }
  }

  private static final class FixtureResult {
    Object value;
    String reason;
    String errorCode;
  }
}
