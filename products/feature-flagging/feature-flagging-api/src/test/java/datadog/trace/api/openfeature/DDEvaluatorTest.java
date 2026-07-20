package datadog.trace.api.openfeature;

import static dev.openfeature.sdk.Reason.ERROR;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DDEvaluatorTest {

  private static final String CANONICAL_FIXTURE_PATH =
      "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data";
  private static final Moshi MOSHI = new Moshi.Builder().add(Date.class, new DateAdapter()).build();
  private static final JsonAdapter<ServerConfiguration> CONFIG_ADAPTER =
      MOSHI.adapter(ServerConfiguration.class);
  private static final Type FIXTURE_LIST_TYPE =
      Types.newParameterizedType(List.class, FixtureCase.class);
  private static final JsonAdapter<List<FixtureCase>> FIXTURE_LIST_ADAPTER =
      MOSHI.adapter(FIXTURE_LIST_TYPE);

  private static Arguments[] valueMappingTestCases() {
    return new Arguments[] {
      // String mappings
      Arguments.of(String.class, "hello", "hello"),
      Arguments.of(String.class, 123, "123"),
      Arguments.of(String.class, true, "true"),
      Arguments.of(String.class, 3.14, "3.14"),
      Arguments.of(String.class, null, null),

      // Boolean mappings
      Arguments.of(Boolean.class, true, true),
      Arguments.of(Boolean.class, false, false),
      Arguments.of(Boolean.class, "true", true),
      Arguments.of(Boolean.class, "false", false),
      Arguments.of(Boolean.class, "TRUE", true),
      Arguments.of(Boolean.class, "FALSE", false),
      Arguments.of(Boolean.class, 1, true),
      Arguments.of(Boolean.class, 0, false),
      Arguments.of(Boolean.class, null, null),

      // Integer mappings
      Arguments.of(Integer.class, 42, 42),
      Arguments.of(Integer.class, "42", 42),
      Arguments.of(Integer.class, 3.14, 3),
      Arguments.of(Integer.class, "3.14", 3),
      Arguments.of(Integer.class, null, null),

      // Double mappings
      Arguments.of(Double.class, 3.14, 3.14),
      Arguments.of(Double.class, "3.14", 3.14),
      Arguments.of(Double.class, 42, 42.0),
      Arguments.of(Double.class, "42", 42.0),
      Arguments.of(Double.class, null, null),

      // Value mappings (OpenFeature Value objects)
      Arguments.of(Value.class, "hello", Value.objectToValue("hello")),
      Arguments.of(Value.class, 42, Value.objectToValue(42)),
      Arguments.of(Value.class, 3.14, Value.objectToValue(3.14)),
      Arguments.of(Value.class, true, Value.objectToValue(true)),
      Arguments.of(Value.class, null, null),

      // Unsupported
      Arguments.of(Date.class, "21-12-2023", IllegalArgumentException.class),
    };
  }

  @ParameterizedTest
  @MethodSource("valueMappingTestCases")
  public void testValueMapping(final Class<?> target, final Object value, final Object expected) {
    if (expected == IllegalArgumentException.class) {
      assertThrows(IllegalArgumentException.class, () -> DDEvaluator.mapValue(target, value));
    } else {
      final Object result = DDEvaluator.mapValue(target, value);
      assertThat(result, equalTo(expected));
    }
  }

  @Test
  public void testEvaluateNoConfig() {
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    final ProviderEvaluation<?> details =
        evaluator.evaluate(Integer.class, "test", 23, mock(EvaluationContext.class));
    assertThat(details.getValue(), equalTo(23));
    assertThat(details.getReason(), equalTo(ERROR.name()));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.PROVIDER_NOT_READY));
  }

  @Test
  public void testInitializeTimesOutWithoutConfig() throws Exception {
    final Runnable configCallback = mock(Runnable.class);
    final DDEvaluator evaluator = new DDEvaluator(configCallback);
    evaluator.accept(null);
    try {
      assertThat(
          evaluator.initialize(10, MILLISECONDS, mock(EvaluationContext.class)), equalTo(false));
      verify(configCallback, times(0)).run();
    } finally {
      evaluator.shutdown();
    }
  }

  @Test
  public void testInitializeSignalsApplicationProviderActivation() throws Exception {
    final FeatureFlaggingGateway.ActivationListener listener =
        mock(FeatureFlaggingGateway.ActivationListener.class);
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    FeatureFlaggingGateway.addActivationListener(listener);
    try {
      evaluator.initialize(1, MILLISECONDS, mock(EvaluationContext.class));

      verify(listener).activate();
    } finally {
      evaluator.shutdown();
      FeatureFlaggingGateway.removeActivationListener(listener);
    }
  }

  @Test
  public void testInitializeWaitsForNonNullConfig() throws Exception {
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    final ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      final Future<Boolean> initialized =
          executor.submit(() -> evaluator.initialize(1, SECONDS, mock(EvaluationContext.class)));

      evaluator.accept(null);
      assertThat(initialized.isDone(), equalTo(false));

      evaluator.accept(mock(ServerConfiguration.class));
      assertThat(initialized.get(1, SECONDS), equalTo(true));
    } finally {
      executor.shutdownNow();
      evaluator.shutdown();
    }
  }

  @Test
  public void testEvaluateNoContext() {
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(mock(ServerConfiguration.class));
    final ProviderEvaluation<?> details = evaluator.evaluate(Integer.class, "test", 23, null);
    assertThat(details.getValue(), equalTo(23));
    assertThat(details.getReason(), equalTo(ERROR.name()));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.INVALID_CONTEXT));
  }

  @Test
  public void testNoAllocations() {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("null-allocation", new Flag("target", true, null, null, null));
    flags.put("empty-allocation", new Flag("target", true, null, null, emptyList()));
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", null, flags));

    final EvaluationContext ctx = new MutableContext("target").setTargetingKey("allocation");

    ProviderEvaluation<?> details = evaluator.evaluate(Integer.class, "null-allocation", 23, ctx);
    assertThat(details.getValue(), equalTo(23));
    assertThat(details.getReason(), equalTo(ERROR.name()));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.GENERAL));

    details = evaluator.evaluate(Integer.class, "empty-allocation", 23, ctx);
    assertThat(details.getValue(), equalTo(23));
    assertThat(details.getReason(), equalTo("DEFAULT"));
    assertThat(details.getErrorCode(), nullValue());
  }

  private static Arguments[] flatteningTestCases() {
    final List<Arguments> arguments = new ArrayList<>();
    arguments.add(Arguments.of(emptyMap(), emptyMap()));
    arguments.add(
        Arguments.of(
            mapOf("integer", 1, "double", 23D, "boolean", true, "string", "string", "null", null),
            mapOf("integer", 1, "double", 23D, "boolean", true, "string", "string", "null", null)));
    arguments.add(
        Arguments.of(
            mapOf("list", asList(1, 2, singletonList(4))),
            mapOf("list[0]", 1, "list[1]", 2, "list[2][0]", 4)));
    arguments.add(
        Arguments.of(
            mapOf("map", mapOf("key1", 1, "key2", 2, "key3", mapOf("key4", 4))),
            mapOf("map.key1", 1, "map.key2", 2, "map.key3.key4", 4)));
    return arguments.toArray(new Arguments[0]);
  }

  @MethodSource("flatteningTestCases")
  @ParameterizedTest
  public void testFlattening(
      final Map<String, Object> attributes, final Map<String, Object> expected) {
    final EvaluationContext context =
        new MutableContext(Value.objectToValue(attributes).asStructure().asMap());
    final Map<String, Object> result = DDEvaluator.flattenContext(context);

    assertThat(result.size(), equalTo(expected.size()));
    for (final Map.Entry<String, Object> entry : expected.entrySet()) {
      assertThat(result, hasEntry(entry.getKey(), entry.getValue()));
    }
  }

  @Test
  public void testCanonicalFixturesArePresent() throws IOException {
    assertThat(canonicalTestCases().size(), greaterThan(0));
  }

  @MethodSource("canonicalTestCases")
  @ParameterizedTest(name = "{0}")
  public void testEvaluateCanonicalFixture(final FixtureCase testCase) throws IOException {
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(loadCanonicalConfiguration());

    final Class<?> targetType = targetType(testCase.variationType);
    final Object defaultValue = mapFixtureValue(targetType, testCase.defaultValue);
    final Object expectedValue = mapFixtureValue(targetType, testCase.result.value);
    final ProviderEvaluation<?> details =
        evaluate(evaluator, targetType, testCase.flag, defaultValue, context(testCase));

    assertThat(details.getValue(), equalTo(expectedValue));
    assertThat(details.getReason(), equalTo(testCase.result.reason));
    if (testCase.result.variant != null) {
      assertThat(details.getVariant(), equalTo(testCase.result.variant));
    }
    if (testCase.result.errorCode != null) {
      assertThat(details.getErrorCode(), equalTo(ErrorCode.valueOf(testCase.result.errorCode)));
    }
    if (testCase.result.flagMetadata != null
        && testCase.result.flagMetadata.get("allocationKey") != null) {
      assertThat(
          details.getFlagMetadata().getString("allocationKey"),
          equalTo(String.valueOf(testCase.result.flagMetadata.get("allocationKey"))));
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static ProviderEvaluation<?> evaluate(
      final DDEvaluator evaluator,
      final Class<?> targetType,
      final String flag,
      final Object defaultValue,
      final EvaluationContext context) {
    return evaluator.evaluate((Class) targetType, flag, defaultValue, context);
  }

  private static ServerConfiguration loadCanonicalConfiguration() throws IOException {
    return CONFIG_ADAPTER.fromJson(read(fixtureRoot().resolve("ufc-config.json")));
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

  private static String read(final Path path) throws IOException {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  private static EvaluationContext context(final FixtureCase testCase) {
    final Map<String, Object> attributes =
        testCase.attributes == null ? emptyMap() : testCase.attributes;
    final MutableContext context =
        new MutableContext(Value.objectToValue(attributes).asStructure().asMap());
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

  private static Object mapFixtureValue(final Class<?> targetType, final Object value) {
    return DDEvaluator.mapValue(targetType, value);
  }

  private static Map<String, Object> mapOf(final Object... props) {
    final Map<String, Object> result = new HashMap<>(props.length << 1);
    int index = 0;
    while (index < props.length) {
      final String key = String.valueOf(props[index++]);
      final Object value = props[index++];
      result.put(key, value);
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
    String variant;
    Map<String, Object> flagMetadata = emptyMap();
  }

  private static final class DateAdapter extends JsonAdapter<Date> {
    @Override
    public Date fromJson(final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      try {
        return Date.from(OffsetDateTime.parse(reader.nextString()).toInstant());
      } catch (final Exception ignored) {
        return null;
      }
    }

    @Override
    public void toJson(final JsonWriter writer, final Date value) throws IOException {
      if (value == null) {
        writer.nullValue();
        return;
      }
      writer.value(value.toInstant().toString());
    }
  }
}
