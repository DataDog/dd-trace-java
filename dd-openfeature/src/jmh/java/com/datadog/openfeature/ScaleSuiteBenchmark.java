package com.datadog.openfeature;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.util.ArrayList;
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
 * Evaluation cost on the opt-in scale suites of the shared FFE fixtures ({@code
 * evaluation-suites/scale} and {@code evaluation-suites/scale-json}): large lists, regex, rules,
 * splits and shards, time windows, many variations and flags, and JSON values up to 10 MB. The
 * dimensions are listed in {@code evaluation-suites/scale/AGENTS.md} of the fixtures. The {@code
 * regex} suite adds literal patterns and inputs that make a backtracking engine slow; it needs the
 * linear-time matcher.
 *
 * <p>Each operation evaluates a case of the selected case file through the provider. With {@code
 * index=all}, the operations go through the cases of the file in turn, so a row is the mean of its
 * best case, worst case and miss. Select one case to measure one shape: the setup prints the index
 * of each case.
 *
 * <p>The setup first evaluates every case once and fails if a value or reason differs from the
 * expected one, so the benchmark never measures a wrong result. JSON values are compared by numeric
 * value: a JSON integer can come back as an integer or as a double.
 *
 * <p>Run all case files: {@code ./gradlew :dd-openfeature:jmh -Pjmh.includes=ScaleSuiteBenchmark
 * -Pjmh.profilers=gc}. Run one case: {@code java -jar dd-openfeature/build/libs/*-jmh.jar
 * ScaleSuiteBenchmark -p cases=scale/splits -p index=0 -prof gc}.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class ScaleSuiteBenchmark {

  /** A case file, as {@code <suite>/<file>} without the {@code .json} extension. */
  @Param({
    "scale/flags",
    "scale/numeric",
    "scale/one-of",
    "scale/one-of-many",
    "scale/regex",
    "scale/regex-many",
    "scale/rules",
    "scale/rules-sweep",
    "scale/semver",
    "scale/splits",
    "scale/time-windows",
    "scale/variations",
    "scale/wide-context",
    "scale-json/json-shapes",
    "scale-json/json-sizes",
    "scale-json/json-values",
    "regex/backtracking",
    "regex/literals"
  })
  public String cases;

  /** The index of one case in the file, or {@code all} to go through every case in turn. */
  @Param({"all"})
  public String index;

  private Provider provider;
  private ScaleCase[] scaleCases;
  private int next;

  @Setup(Level.Trial)
  public void setUp() throws IOException {
    final int slash = cases.indexOf('/');
    final ScaleSuites.Suite suite = ScaleSuites.load(cases.substring(0, slash));
    final DDEvaluator evaluator =
        new DDEvaluator(
            () -> {},
            AgentBridge.NONE,
            Settings.load(AgentBridge.NONE),
            ExperimentationOptOutMode.DISABLED);
    evaluator.accept(UniversalFlagConfigParser.parse(suite.config()));
    provider = new Provider(new Provider.Options(), evaluator, false);

    final List<ScaleCase> loaded = new ArrayList<>();
    for (final Object json : suite.cases(cases.substring(slash + 1))) {
      loaded.add(new ScaleCase((Map<?, ?>) json));
    }
    for (int i = 0; i < loaded.size(); i++) {
      final ScaleCase scaleCase = loaded.get(i);
      scaleCase.check(scaleCase.evaluate(provider));
      System.out.println("[" + cases + "] case " + i + ": " + scaleCase.describe());
    }
    scaleCases =
        "all".equals(index)
            ? loaded.toArray(new ScaleCase[0])
            : new ScaleCase[] {loaded.get(Integer.parseInt(index))};
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
    private final String targetingKey;
    private final EvaluationContext context;

    ScaleCase(final Map<?, ?> json) {
      this.flag = (String) json.get("flag");
      this.variationType = (String) json.get("variationType");
      final Map<?, ?> result = (Map<?, ?>) json.get("result");
      this.defaultValue = typed(json.get("defaultValue"));
      this.expectedValue = result.get("value");
      this.expectedReason = (String) result.get("reason");
      this.targetingKey = (String) json.get("targetingKey");
      final MutableContext context =
          new MutableContext(Value.objectToValue(json.get("attributes")).asStructure().asMap());
      context.setTargetingKey(targetingKey);
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
      final Object actual =
          details.getValue() instanceof Value
              ? plain((Value) details.getValue())
              : details.getValue();
      if (!sameValue(expectedValue, actual)
          || !Objects.equals(expectedReason, details.getReason())) {
        throw new IllegalStateException(
            "Unexpected result for "
                + flag
                + " ("
                + targetingKey
                + "): expected "
                + expectedReason
                + " "
                + abbreviate(expectedValue)
                + ", got "
                + details.getReason()
                + " "
                + abbreviate(actual));
      }
    }

    String describe() {
      return flag + " (" + targetingKey + ") -> " + expectedReason;
    }

    /** Converts a fixture value to the type that the provider takes for this variation type. */
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
          return Value.objectToValue(value);
        default:
          return value;
      }
    }
  }

  /** Converts a returned value to maps, lists, strings, numbers, booleans and null. */
  private static Object plain(final Value value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isStructure()) {
      final Map<String, Object> map = new java.util.LinkedHashMap<>();
      value.asStructure().asMap().forEach((k, v) -> map.put(k, plain(v)));
      return map;
    }
    if (value.isList()) {
      final List<Object> list = new ArrayList<>();
      for (final Value item : value.asList()) {
        list.add(plain(item));
      }
      return list;
    }
    return value.asObject();
  }

  /** Equality that compares numbers by value, so 5 and 5.0 are the same. */
  private static boolean sameValue(final Object expected, final Object actual) {
    if (expected instanceof Number && actual instanceof Number) {
      return new java.math.BigDecimal(expected.toString())
              .compareTo(new java.math.BigDecimal(actual.toString()))
          == 0;
    }
    if (expected instanceof Map && actual instanceof Map) {
      final Map<?, ?> e = (Map<?, ?>) expected;
      final Map<?, ?> a = (Map<?, ?>) actual;
      if (e.size() != a.size()) {
        return false;
      }
      for (final Map.Entry<?, ?> entry : e.entrySet()) {
        if (!a.containsKey(entry.getKey()) || !sameValue(entry.getValue(), a.get(entry.getKey()))) {
          return false;
        }
      }
      return true;
    }
    if (expected instanceof List && actual instanceof List) {
      final List<?> e = (List<?>) expected;
      final List<?> a = (List<?>) actual;
      if (e.size() != a.size()) {
        return false;
      }
      for (int i = 0; i < e.size(); i++) {
        if (!sameValue(e.get(i), a.get(i))) {
          return false;
        }
      }
      return true;
    }
    return Objects.equals(expected, actual);
  }

  private static String abbreviate(final Object value) {
    final String text = String.valueOf(value);
    return text.length() <= 200 ? text : text.substring(0, 200) + "... (" + text.length() + ")";
  }
}
