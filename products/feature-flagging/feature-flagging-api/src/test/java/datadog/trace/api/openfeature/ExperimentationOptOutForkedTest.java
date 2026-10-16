package datadog.trace.api.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.featureflag.ufc.v1.Feature;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs the shared contract through the provider API and the real Java evaluator. */
class ExperimentationOptOutForkedTest {
  static {
    // Preserve ordinary allocation serial IDs in metadata so all identities can be asserted.
    System.setProperty("dd.experimental.flagging.provider.span.enrichment.enabled", "true");
  }

  private static final Moshi MOSHI = new Moshi.Builder().add(Date.class, new DateAdapter()).build();
  private static final JsonAdapter<ServerConfiguration> CONFIG_ADAPTER =
      MOSHI.adapter(ServerConfiguration.class);
  private static final JsonAdapter<List<Case>> CASE_ADAPTER =
      MOSHI.adapter(Types.newParameterizedType(List.class, Case.class));

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void sharedContract(final Case testCase) throws Exception {
    final DDEvaluator evaluator = evaluator(testCase);
    final Provider provider = new Provider(options(testCase), evaluator);
    try {
      assertCase(testCase, evaluate(provider, testCase));
    } finally {
      provider.shutdown();
    }
  }

  @Test
  void executesEveryManifestCase() throws Exception {
    final List<Case> cases = cases();
    final Manifest manifest = manifest();
    assertEquals(136, manifest.expectedCounts.get("evaluations").intValue());
    assertEquals(manifest.expectedCounts.get("evaluations").intValue(), cases.size());
    assertEquals(cases.size(), cases.stream().map(item -> item.id).distinct().count());
    for (final Case testCase : cases) {
      final Value value = context(testCase).getValue("dd_experimentation_opt_out");
      final boolean effective =
          Boolean.TRUE.equals(testCase.providerOptions.get("enableExperimentationOptOutParameter"))
              && value != null
              && value.isBoolean()
              && Boolean.TRUE.equals(value.asBoolean());
      assertEquals(testCase.expected.effectiveExperimentationOptOut, effective, testCase.id);
    }
  }

  @Test
  void eligibleSubjectsKeepExactAssignmentIdentityAndReason() throws Exception {
    final Manifest manifest = manifest();
    final Set<String> ids = new HashSet<>(manifest.invariants.eligibleParityCaseIds);
    ids.addAll(manifest.compilerGeneration.eligibleParityCaseIds);
    int checked = 0;
    for (final Case testCase : cases()) {
      if (!ids.contains(testCase.id)) {
        continue;
      }
      final DDEvaluator evaluator = evaluator(testCase);
      final Provider baseline = new Provider(new Provider.Options(), evaluator);
      final Provider candidate = new Provider(options(testCase), evaluator);
      try {
        final ProviderEvaluation<?> before = evaluate(baseline, testCase);
        final ProviderEvaluation<?> after = evaluate(candidate, testCase);
        assertCase(testCase, before);
        assertCase(testCase, after);
        assertEquals(before.getReason(), after.getReason(), testCase.id);
        assertEquals(
            before.getFlagMetadata().asUnmodifiableMap(),
            after.getFlagMetadata().asUnmodifiableMap(),
            testCase.id);
        checked++;
      } finally {
        baseline.shutdown();
        candidate.shutdown();
      }
    }
    assertEquals(47, checked);
    assertEquals(ids.size(), checked);
  }

  @Test
  void concurrentSubjectsUseIndependentOptions() throws Exception {
    final Manifest manifest = manifest();
    final Map<String, Case> byId =
        cases().stream().collect(Collectors.toMap(item -> item.id, item -> item));
    final Case eligible = byId.get(manifest.invariants.concurrentServerCaseIds.get(0));
    final Case excluded = byId.get(manifest.invariants.concurrentServerCaseIds.get(1));
    final Provider provider = new Provider(options(excluded), evaluator(excluded));
    final ExecutorService executor = Executors.newFixedThreadPool(8);
    try {
      final List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < manifest.invariants.concurrentServerRepetitions; i++) {
        results.add(executor.submit(() -> assertCase(eligible, evaluate(provider, eligible))));
        results.add(executor.submit(() -> assertCase(excluded, evaluate(provider, excluded))));
      }
      for (final Future<?> result : results) {
        result.get();
      }
      assertEquals(128, results.size());
    } finally {
      executor.shutdownNow();
      provider.shutdown();
    }
  }

  @Test
  void activationIsCapturedAtProviderConstruction() throws Exception {
    final Case testCase = caseById("excluded-control");
    final Provider.Options options = new Provider.Options();
    final DDEvaluator evaluator = evaluator(testCase);
    final Provider disabled = new Provider(options, evaluator);
    options.enableExperimentationOptOutParameter(true);
    final Provider enabled = new Provider(options, evaluator);
    options.enableExperimentationOptOutParameter(false);
    try {
      assertEquals(
          "experiment", evaluate(disabled, testCase).getFlagMetadata().getString("allocationKey"));
      assertCase(testCase, evaluate(enabled, testCase));
    } finally {
      disabled.shutdown();
      enabled.shutdown();
    }
  }

  @Test
  void legacyMissingDoLogKeepsItsOrdinaryBehavior() throws Exception {
    final Case testCase = caseById("missing-dolog-excluded");
    final Provider provider = new Provider(new Provider.Options(), evaluator(testCase));
    try {
      final ProviderEvaluation<?> result = evaluate(provider, testCase);
      assertEquals("experimental-control", result.getValue());
      assertEquals("experiment", result.getFlagMetadata().getString("allocationKey"));
      assertFalse(result.getFlagMetadata().getBoolean(DDEvaluator.METADATA_DO_LOG));
    } finally {
      provider.shutdown();
    }
  }

  @Test
  void olderAgentWithoutCapabilityFieldReturnsDefaultInsteadOfLinkageFailure() throws Exception {
    final ClassLoader loader = new LegacyConfigurationClassLoader();
    final Class<?> configurationType = loader.loadClass(ServerConfiguration.class.getName());
    assertThrows(
        NoSuchFieldException.class, () -> configurationType.getField("evaluationCapabilities"));
    final Object configuration =
        configurationType
            .getConstructor(
                String.class,
                String.class,
                Boolean.class,
                datadog.trace.api.featureflag.ufc.v1.Environment.class,
                Map.class)
            .newInstance("", "SERVER", false, null, new HashMap<>());
    final Class<?> evaluatorType = loader.loadClass(DDEvaluator.class.getName());
    final Constructor<?> constructor = evaluatorType.getConstructor(Runnable.class);
    constructor.setAccessible(true);
    final Object evaluator = constructor.newInstance((Runnable) () -> {});
    final Method accept = evaluatorType.getMethod("accept", configurationType);
    accept.setAccessible(true);
    accept.invoke(evaluator, configuration);
    final Method evaluate =
        evaluatorType.getMethod(
            "evaluate",
            Class.class,
            String.class,
            Object.class,
            dev.openfeature.sdk.EvaluationContext.class,
            boolean.class);
    evaluate.setAccessible(true);
    final ProviderEvaluation<?> result =
        (ProviderEvaluation<?>)
            evaluate.invoke(
                evaluator,
                String.class,
                "flag",
                "application-default",
                new MutableContext("subject"),
                true);
    assertEquals("application-default", result.getValue());
    assertEquals(ErrorCode.GENERAL, result.getErrorCode());
    assertEquals("ERROR", result.getReason());
  }

  private static void assertCase(final Case testCase, final ProviderEvaluation<?> result) {
    assertEquals(testCase.expected.value, result.getValue(), testCase.id);
    if (testCase.expected.assignment == null) {
      assertNull(result.getFlagMetadata().getString("allocationKey"), testCase.id);
      if ("no-assignment".equals(testCase.expected.outcome)) {
        assertNull(result.getErrorCode(), testCase.id);
        assertEquals("DEFAULT", result.getReason(), testCase.id);
      } else {
        assertEquals("ERROR", result.getReason(), testCase.id);
        assertEquals(
            "invalid-configuration".equals(testCase.expected.outcome)
                ? ErrorCode.PARSE_ERROR
                : ErrorCode.GENERAL,
            result.getErrorCode(),
            testCase.id);
      }
      return;
    }
    final Assignment expected = testCase.expected.assignment;
    assertNull(result.getErrorCode(), testCase.id);
    assertEquals(expected.variationKey, result.getVariant(), testCase.id);
    assertEquals(
        expected.allocationKey, result.getFlagMetadata().getString("allocationKey"), testCase.id);
    assertEquals(
        expected.doLog,
        result.getFlagMetadata().getBoolean(DDEvaluator.METADATA_DO_LOG),
        testCase.id);
    assertEquals(
        expected.serialId,
        result.getFlagMetadata().getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID),
        testCase.id);
    final Map<String, Object> expectedFeatures = new HashMap<>();
    final List<Feature> features = expected.features == null ? emptyList() : expected.features;
    for (final Feature feature : features) {
      for (final String destination : feature.destinations) {
        final String prefix;
        switch (destination) {
          case "HOOK":
            prefix = DDEvaluator.METADATA_FEATURE_PREFIX;
            break;
          case "EXPOSURE":
            prefix = DDEvaluator.METADATA_EXPOSURE_FEATURE_PREFIX;
            break;
          case "EVALUATION":
            prefix = DDEvaluator.METADATA_EVALUATION_FEATURE_PREFIX;
            break;
          default:
            throw new AssertionError("Unknown fixture destination " + destination);
        }
        expectedFeatures.put(prefix + feature.key, feature.value);
      }
    }
    final Map<String, Object> actualFeatures = new HashMap<>();
    result
        .getFlagMetadata()
        .asUnmodifiableMap()
        .forEach(
            (key, value) -> {
              if (key.startsWith(DDEvaluator.METADATA_FEATURE_PREFIX)
                  || key.startsWith(DDEvaluator.METADATA_EXPOSURE_FEATURE_PREFIX)
                  || key.startsWith(DDEvaluator.METADATA_EVALUATION_FEATURE_PREFIX)) {
                actualFeatures.put(key, value);
              }
            });
    assertEquals(expectedFeatures, actualFeatures, testCase.id);
  }

  private static ProviderEvaluation<?> evaluate(final Provider provider, final Case testCase) {
    final MutableContext context = context(testCase);
    switch (testCase.evaluation.variationType) {
      case "STRING":
        return provider.getStringEvaluation(
            testCase.evaluation.flag, (String) testCase.evaluation.defaultValue, context);
      case "BOOLEAN":
        return provider.getBooleanEvaluation(
            testCase.evaluation.flag, (Boolean) testCase.evaluation.defaultValue, context);
      default:
        throw new AssertionError("Unsupported fixture type " + testCase.evaluation.variationType);
    }
  }

  private static MutableContext context(final Case testCase) {
    return new MutableContext(
        testCase.context.targetingKey,
        Value.objectToValue(testCase.context.attributes).asStructure().asMap());
  }

  private static Provider.Options options(final Case testCase) {
    return new Provider.Options()
        .enableExperimentationOptOutParameter(
            Boolean.TRUE.equals(
                testCase.providerOptions.get("enableExperimentationOptOutParameter")))
        .enableEvaluationTelemetryOptOutParameter(
            Boolean.TRUE.equals(
                testCase.providerOptions.get("enableEvaluationTelemetryOptOutParameter")));
  }

  private static DDEvaluator evaluator(final Case testCase) throws IOException {
    final DDEvaluator evaluator =
        new DDEvaluator(
            () -> {}, Clock.fixed(Instant.parse(testCase.evaluation.now), ZoneOffset.UTC));
    final Configuration configuration =
        manifest().configurations.stream()
            .filter(item -> item.id.equals(testCase.configuration))
            .findFirst()
            .orElseThrow(AssertionError::new);
    evaluator.accept(CONFIG_ADAPTER.fromJson(read(root().resolve(configuration.path))));
    return evaluator;
  }

  private static Case caseById(final String id) throws IOException {
    return cases().stream()
        .filter(item -> item.id.equals(id))
        .findFirst()
        .orElseThrow(AssertionError::new);
  }

  private static List<Case> cases() throws IOException {
    final List<Case> result = new ArrayList<>();
    for (final CaseFile file : manifest().evaluationCases) {
      final List<Case> items = CASE_ADAPTER.fromJson(read(root().resolve(file.path)));
      assertNotNull(items);
      assertEquals(
          file.ids, items.stream().map(item -> item.id).collect(Collectors.toList()), file.path);
      result.addAll(items);
    }
    return result;
  }

  private static Manifest manifest() throws IOException {
    return MOSHI.adapter(Manifest.class).fromJson(read(root().resolve("manifest.json")));
  }

  private static Path root() {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path root =
          directory.resolve(
              "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data/experimentation-opt-out/v1");
      if (Files.exists(root.resolve("manifest.json"))) {
        return root;
      }
      directory = directory.getParent();
    }
    throw new AssertionError("Initialize the pinned FFE fixture submodule");
  }

  private static String read(final Path path) throws IOException {
    return new String(Files.readAllBytes(path), UTF_8);
  }

  static class Manifest {
    List<Configuration> configurations;
    List<CaseFile> evaluationCases;
    Map<String, Integer> expectedCounts;
    Invariants invariants;
    CompilerGeneration compilerGeneration;
  }

  static class Configuration {
    String id;
    String path;
  }

  static class CaseFile {
    String path;
    List<String> ids;
  }

  static class Invariants {
    List<String> eligibleParityCaseIds;
    List<String> concurrentServerCaseIds;
    int concurrentServerRepetitions;
  }

  static class CompilerGeneration {
    List<String> eligibleParityCaseIds;
  }

  static class Case {
    String id;
    String configuration;
    Map<String, Boolean> providerOptions;
    Context context;
    Evaluation evaluation;
    Expected expected;

    @Override
    public String toString() {
      return id;
    }
  }

  static class Context {
    String targetingKey;
    Map<String, Object> attributes;
  }

  static class Evaluation {
    String flag;
    String variationType;
    Object defaultValue;
    String now;
  }

  static class Expected {
    boolean effectiveExperimentationOptOut;
    String outcome;
    Object value;
    Assignment assignment;
  }

  static class Assignment {
    String allocationKey;
    String variationKey;
    Boolean doLog;
    Integer serialId;
    List<Feature> features;
  }

  static class DateAdapter extends JsonAdapter<Date> {
    @Override
    public Date fromJson(final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      return Date.from(Instant.parse(reader.nextString()));
    }

    @Override
    public void toJson(final JsonWriter writer, final Date value) throws IOException {
      if (value == null) {
        writer.nullValue();
      } else {
        writer.value(value.toInstant().toString());
      }
    }
  }

  /** Loads the provider against a bootstrap configuration class that lacks the new field. */
  static class LegacyConfigurationClassLoader extends ClassLoader {
    LegacyConfigurationClassLoader() {
      super(ExperimentationOptOutForkedTest.class.getClassLoader());
    }

    @Override
    protected synchronized Class<?> loadClass(final String name, final boolean resolve)
        throws ClassNotFoundException {
      if (!name.startsWith("datadog.trace.api.openfeature.")
          && !name.equals(ServerConfiguration.class.getName())) {
        return super.loadClass(name, resolve);
      }
      Class<?> type = findLoadedClass(name);
      if (type == null) {
        try (InputStream source =
            getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
          assertNotNull(source, name);
          final byte[] bytes = source.readAllBytes();
          if (name.equals(ServerConfiguration.class.getName())) {
            final byte[] field = "evaluationCapabilities".getBytes(UTF_8);
            final byte[] legacy = "obsoleteCapabilities__".getBytes(UTF_8);
            assertEquals(field.length, legacy.length);
            int replacements = 0;
            for (int i = 0; i <= bytes.length - field.length; i++) {
              boolean matches = true;
              for (int j = 0; j < field.length; j++) {
                matches &= bytes[i + j] == field[j];
              }
              if (matches) {
                System.arraycopy(legacy, 0, bytes, i, legacy.length);
                replacements++;
              }
            }
            assertTrue(replacements > 0);
          }
          type = defineClass(name, bytes, 0, bytes.length);
        } catch (final IOException e) {
          throw new ClassNotFoundException(name, e);
        }
      }
      if (resolve) {
        resolveClass(type);
      }
      return type;
    }
  }
}
