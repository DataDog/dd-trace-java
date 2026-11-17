package com.datadog.openfeature;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Evaluation cost on the opt-in scale suite of the shared FFE fixtures ({@code
 * evaluation-suites/scale}): 10,000-entry lists, regex conditions on 2 KB inputs, deep rules, a
 * 200-attribute context and JSON values with hundreds of nodes.
 *
 * <p>Each operation evaluates the next case of the selected case file through the provider. The
 * setup first evaluates every case once and fails if a value or reason differs from the expected
 * one, so the benchmark never measures a wrong result.
 *
 * <p>Run: {@code ./gradlew :dd-openfeature:jmh -Pjmh.includes=ScaleSuiteBenchmark}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class ScaleSuiteBenchmark {
  private static final String SUITE_PATH =
      "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data/evaluation-suites/scale";
  private static final JsonFactory JSON_FACTORY = new JsonFactory();

  /** The case file of the scale suite, without the {@code .json} extension. */
  @Param({"json-values", "one-of", "regex", "rules", "wide-context"})
  public String cases;

  private Provider provider;
  private ScaleCase[] scaleCases;
  private int next;

  @Setup(Level.Trial)
  public void setUp() throws IOException {
    final Path suite = suiteRoot();
    final DDEvaluator evaluator =
        new DDEvaluator(
            () -> {},
            AgentBridge.NONE,
            Settings.load(AgentBridge.NONE),
            ExperimentationOptOutMode.DISABLED);
    evaluator.accept(
        UniversalFlagConfigParser.parse(Files.readAllBytes(suite.resolve("ufc-config.json"))));
    provider = new Provider(new Provider.Options(), evaluator, false);

    final List<ScaleCase> loaded = new ArrayList<>();
    for (final Object json : (List<?>) read(suite.resolve("cases").resolve(cases + ".json"))) {
      loaded.add(new ScaleCase((Map<?, ?>) json));
    }
    scaleCases = loaded.toArray(new ScaleCase[0]);
    for (final ScaleCase scaleCase : scaleCases) {
      scaleCase.check(scaleCase.evaluate(provider));
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    provider.shutdown();
  }

  @Benchmark
  public ProviderEvaluation<?> evaluate() {
    final ScaleCase scaleCase = scaleCases[next];
    next = next + 1 == scaleCases.length ? 0 : next + 1;
    return scaleCase.evaluate(provider);
  }

  /** One evaluation case, with its expected value and reason. */
  private static final class ScaleCase {
    private final String flag;
    private final String variationType;
    private final Object defaultValue;
    private final Object expectedValue;
    private final String expectedReason;
    private final EvaluationContext context;

    ScaleCase(final Map<?, ?> json) {
      this.flag = (String) json.get("flag");
      this.variationType = (String) json.get("variationType");
      final Map<?, ?> result = (Map<?, ?>) json.get("result");
      this.defaultValue = typed(json.get("defaultValue"));
      this.expectedValue = typed(result.get("value"));
      this.expectedReason = (String) result.get("reason");
      final MutableContext context =
          new MutableContext(Value.objectToValue(json.get("attributes")).asStructure().asMap());
      context.setTargetingKey((String) json.get("targetingKey"));
      this.context = context;
    }

    ProviderEvaluation<?> evaluate(final Provider provider) {
      switch (variationType) {
        case "BOOLEAN":
          return provider.getBooleanEvaluation(flag, (Boolean) defaultValue, context);
        case "INTEGER":
          return provider.getIntegerEvaluation(flag, (Integer) defaultValue, context);
        case "NUMERIC":
          return provider.getDoubleEvaluation(flag, (Double) defaultValue, context);
        case "STRING":
          return provider.getStringEvaluation(flag, (String) defaultValue, context);
        case "JSON":
          return provider.getObjectEvaluation(flag, (Value) defaultValue, context);
        default:
          throw new IllegalArgumentException("Unsupported variationType: " + variationType);
      }
    }

    void check(final ProviderEvaluation<?> details) {
      if (!Objects.equals(expectedValue, details.getValue())
          || !Objects.equals(expectedReason, details.getReason())) {
        throw new IllegalStateException(
            "Unexpected result for "
                + flag
                + ": expected "
                + expectedReason
                + " "
                + expectedValue
                + ", got "
                + details.getReason()
                + " "
                + details.getValue());
      }
    }

    /** Converts a fixture value to the type that the provider returns for this variation type. */
    private Object typed(final Object value) {
      if (value == null) {
        return null;
      }
      switch (variationType) {
        case "INTEGER":
          return ((Number) value).intValue();
        case "NUMERIC":
          return ((Number) value).doubleValue();
        case "JSON":
          return Value.objectToValue(exactIntegers(value));
        default:
          return value;
      }
    }
  }

  /** Reads integral numbers as Integer when they fit, as the UFC parser does for JSON variants. */
  private static Object exactIntegers(final Object value) {
    if (value instanceof Long) {
      final long number = (Long) value;
      return number == (int) number ? (Object) (int) number : value;
    }
    if (value instanceof Map) {
      final Map<String, Object> map = new LinkedHashMap<>();
      ((Map<?, ?>) value).forEach((k, v) -> map.put((String) k, exactIntegers(v)));
      return map;
    }
    if (value instanceof List) {
      final List<Object> list = new ArrayList<>();
      ((List<?>) value).forEach(v -> list.add(exactIntegers(v)));
      return list;
    }
    return value;
  }

  /** Reads a JSON file: integral numbers as Long, other numbers as Double. */
  private static Object read(final Path file) throws IOException {
    try (JsonParser parser = JSON_FACTORY.createParser(Files.readAllBytes(file))) {
      parser.nextToken();
      return readValue(parser);
    }
  }

  private static Object readValue(final JsonParser parser) throws IOException {
    final JsonToken token = parser.currentToken();
    switch (token) {
      case START_OBJECT:
        final Map<String, Object> object = new LinkedHashMap<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
          final String name = parser.currentName();
          parser.nextToken();
          object.put(name, readValue(parser));
        }
        return object;
      case START_ARRAY:
        final List<Object> array = new ArrayList<>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
          array.add(readValue(parser));
        }
        return array;
      case VALUE_STRING:
        return parser.getText();
      case VALUE_NUMBER_INT:
        return parser.getLongValue();
      case VALUE_NUMBER_FLOAT:
        return parser.getDoubleValue();
      case VALUE_TRUE:
        return Boolean.TRUE;
      case VALUE_FALSE:
        return Boolean.FALSE;
      case VALUE_NULL:
        return null;
      default:
        throw new IOException("Unexpected JSON token: " + token);
    }
  }

  private static Path suiteRoot() {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path candidate = directory.resolve(SUITE_PATH);
      if (Files.exists(candidate.resolve("ufc-config.json"))) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException(
        "Unable to find the FFE scale suite: initialize the ffe-system-test-data submodule");
  }
}
