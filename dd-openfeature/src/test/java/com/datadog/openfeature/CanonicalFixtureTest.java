package com.datadog.openfeature;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.datadog.openfeature.internal.ConfigurationService;
import com.datadog.openfeature.internal.FeatureFlagsRuntime;
import com.datadog.openfeature.internal.JsonReading;
import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.evaluation.ExperimentationOptOut;
import com.datadog.openfeature.internal.exposure.ExposureDeduplicationCache;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.exposure.ExposurePipeline;
import com.datadog.openfeature.internal.flagevaluation.FlagEvalEvent;
import com.datadog.openfeature.internal.flagevaluation.FlagEvaluationPipeline;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Runs the shared FFE evaluation suites through the production UFC parser and evaluator. */
class CanonicalFixtureTest {
  private static final String PLATFORM = "java";
  private static final String FIXTURE_PATH =
      "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data";
  private static final FixtureSuite CORE =
      new FixtureSuite("core", "ufc-config.json", "evaluation-cases");
  private static final FixtureSuite DEPENDENT_FLAGS =
      new FixtureSuite(
          "dependent-flags",
          "evaluation-suites/dependent-flags/ufc-config.json",
          "evaluation-suites/dependent-flags/cases");
  private static final List<FixtureSuite> SUITES = Arrays.asList(CORE, DEPENDENT_FLAGS);

  @Test
  void canonicalFixturesArePresent() throws IOException {
    assertThat(canonicalTestCases().size(), greaterThan(0));
  }

  @Test
  void dependentFlagFixturesArePresent() throws IOException {
    assertThat(testCases(DEPENDENT_FLAGS).size(), greaterThan(0));
  }

  @Test
  void eventMatchersGreedilyConsumeMatchingEvents() {
    final Map<String, Object> matcher = new LinkedHashMap<>();
    matcher.put("flag", "expected");
    final Map<String, Object> expectation = new LinkedHashMap<>();
    expectation.put("evaluationEvents", Collections.singletonList(matcher));
    final FixtureExpectations expectations = new FixtureExpectations(expectation);
    final List<FlagEvalEvent> actual =
        Arrays.asList(
            new FlagEvalEvent("unmatched", null, null, null, 0, emptyMap()),
            new FlagEvalEvent("expected", null, null, null, 0, emptyMap()));

    expectations.assertMatches(Collections.emptyList(), actual);

    expectation.put("noUnmatchedEvents", true);
    final FixtureExpectations exactExpectations = new FixtureExpectations(expectation);
    assertThrows(
        AssertionError.class,
        () -> exactExpectations.assertMatches(Collections.emptyList(), actual));
  }

  @Test
  void platformSelectorsControlApplicability() {
    final Map<String, Object> javaSelector =
        Collections.singletonMap("platform", (Object) PLATFORM);
    final Map<String, Object> webSelector = Collections.singletonMap("platform", (Object) "web");

    assertFalse(
        isApplicable(Collections.singletonMap("_skip", Collections.singletonList(javaSelector))));
    assertTrue(
        isApplicable(
            Collections.singletonMap("_include", Collections.singletonList(javaSelector))));
    assertTrue(
        isApplicable(Collections.singletonMap("_skip", Collections.singletonList(webSelector))));
    assertFalse(
        isApplicable(Collections.singletonMap("_include", Collections.singletonList(webSelector))));
  }

  @Test
  void eventMatchersEnforceCounts() {
    final Map<String, Object> matcher = new LinkedHashMap<>();
    matcher.put("flag", "expected");
    matcher.put("_count", 2);
    final Map<String, Object> expectation = new LinkedHashMap<>();
    expectation.put("evaluationEvents", Collections.singletonList(matcher));
    expectation.put("noUnmatchedEvents", true);
    final FixtureExpectations expectations = new FixtureExpectations(expectation);
    final FlagEvalEvent event = new FlagEvalEvent("expected", null, null, null, 0, emptyMap());

    expectations.assertMatches(Collections.emptyList(), Arrays.asList(event, event));
    assertThrows(
        AssertionError.class,
        () ->
            expectations.assertMatches(Collections.emptyList(), Collections.singletonList(event)));
  }

  @MethodSource("canonicalTestCases")
  @ParameterizedTest(name = "{0}")
  void evaluateCanonicalFixture(final FixtureCase testCase) throws Exception {
    evaluateCanonicalFixture(testCase, ExperimentationOptOutMode.DISABLED);
  }

  @MethodSource("immediateOptOutTestCases")
  @ParameterizedTest(name = "immediate: {0}")
  void evaluateImmediateOptOutFixture(final FixtureCase testCase) throws Exception {
    evaluateCanonicalFixture(testCase, ExperimentationOptOutMode.IMMEDIATE);
  }

  @MethodSource("futureOptOutTestCases")
  @ParameterizedTest(name = "future: {0}")
  void evaluateFutureOptOutFixture(final FixtureCase testCase) throws Exception {
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
    for (final FixtureCase testCase : testCases(CORE)) {
      final Map<String, Object> capabilities =
          (Map<String, Object>) testCase.json.get("capabilities");
      if (capabilities != null && capabilities.containsKey(capability)) {
        final Map<String, Object> json = new LinkedHashMap<>(testCase.json);
        json.put("result", capabilities.get(capability));
        cases.add(new FixtureCase(testCase.suite, testCase.fileName, testCase.index, json));
      }
    }
    assertEquals(expectedCount, cases.size(), "The opt-out capability fixtures must execute");
    return cases;
  }

  private void evaluateCanonicalFixture(
      final FixtureCase testCase, final ExperimentationOptOutMode mode) throws Exception {
    final Class<?> targetType = targetType(testCase.variationType);
    final Object defaultValue = fixtureValue(targetType, testCase.defaultValue);
    final Object expectedValue = fixtureValue(targetType, testCase.resultValue);
    final List<FlagEvalEvent> evaluationEvents = new ArrayList<>();
    final boolean captureTelemetry =
        testCase.expectations != null && mode == ExperimentationOptOutMode.DISABLED;
    final FeatureFlagsRuntime runtime =
        captureTelemetry ? capturingRuntime(new CapturingPipeline(evaluationEvents)) : null;
    final FixtureEvaluator evaluator =
        new FixtureEvaluator(
            UniversalFlagConfigParser.parse(
                Files.readAllBytes(fixtureRoot().resolve(testCase.suite.ufcPath))),
            mode,
            runtime,
            captureTelemetry);
    final Provider provider =
        new Provider(new Provider.Options().experimentationOptOutMode(mode), evaluator, false);
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    final FlagEvaluationDetails<?> details;
    try {
      api.setProviderAndWait(provider);
      details =
          evaluate(api.getClient(), targetType, testCase.flag, defaultValue, context(testCase));
    } finally {
      api.shutdown();
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
    final Map<String, Object> metadata = details.getFlagMetadata().asUnmodifiableMap();
    if (testCase.checksSerialId) {
      if (testCase.resultSerialId == null) {
        assertFalse(metadata.containsKey("__dd_split_serial_id"));
      } else {
        assertEquals(testCase.resultSerialId.intValue(), metadata.get("__dd_split_serial_id"));
      }
    }
    if (testCase.resultDoLog != null) {
      assertEquals(testCase.resultDoLog, metadata.get("__dd_do_log"));
    }
    if (testCase.resultAllocationKey != null) {
      assertThat(
          details.getFlagMetadata().getString("allocationKey"),
          equalTo(testCase.resultAllocationKey));
    }
    if (testCase.expectations != null) {
      testCase.expectations.assertMatches(evaluator.exposures, evaluationEvents);
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

  private static FlagEvaluationDetails<?> evaluate(
      final Client client,
      final Class<?> targetType,
      final String flag,
      final Object defaultValue,
      final EvaluationContext context) {
    if (targetType == Boolean.class) {
      return client.getBooleanDetails(flag, (Boolean) defaultValue, context);
    }
    if (targetType == Integer.class) {
      return client.getIntegerDetails(flag, (Integer) defaultValue, context);
    }
    if (targetType == Double.class) {
      return client.getDoubleDetails(flag, (Double) defaultValue, context);
    }
    if (targetType == String.class) {
      return client.getStringDetails(flag, (String) defaultValue, context);
    }
    return client.getObjectDetails(flag, (Value) defaultValue, context);
  }

  /** Creates a runtime only holding a flag-evaluation pipeline, without starting it. */
  private static FeatureFlagsRuntime capturingRuntime(final FlagEvaluationPipeline evaluations)
      throws ReflectiveOperationException {
    final Constructor<FeatureFlagsRuntime> constructor =
        FeatureFlagsRuntime.class.getDeclaredConstructor(
            ConfigurationService.class, ExposurePipeline.class, FlagEvaluationPipeline.class);
    constructor.setAccessible(true);
    return constructor.newInstance(mock(ConfigurationService.class), null, evaluations);
  }

  static List<FixtureCase> canonicalTestCases() throws IOException {
    final List<FixtureCase> result = new ArrayList<>();
    for (final FixtureSuite suite : SUITES) {
      result.addAll(testCases(suite));
    }
    assertThat(result.size(), greaterThan(0));
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<FixtureCase> testCases(final FixtureSuite suite) throws IOException {
    final List<FixtureCase> result = new ArrayList<>();
    try (Stream<Path> paths = Files.list(fixtureRoot().resolve(suite.caseDirectory))) {
      final List<Path> files =
          paths
              .filter(path -> path.getFileName().toString().endsWith(".json"))
              .sorted()
              .collect(Collectors.toList());
      for (final Path file : files) {
        final List<Object> cases = (List<Object>) JsonReading.read(Files.readAllBytes(file));
        for (int index = 0; index < cases.size(); index++) {
          final Map<String, Object> testCase = (Map<String, Object>) cases.get(index);
          if (isApplicable(testCase)) {
            result.add(new FixtureCase(suite, file.getFileName().toString(), index, testCase));
          }
        }
      }
    }
    return result;
  }

  private static boolean isApplicable(final Map<String, Object> item) {
    if (selectsPlatform(item.get("_skip"))) {
      return false;
    }
    return !item.containsKey("_include") || selectsPlatform(item.get("_include"));
  }

  private static boolean selectsPlatform(final Object selectors) {
    if (!(selectors instanceof List)) {
      return false;
    }
    for (final Object selector : (List<?>) selectors) {
      if (selector instanceof Map
          && PLATFORM.equals(String.valueOf(((Map<?, ?>) selector).get("platform")))) {
        return true;
      }
    }
    return false;
  }

  private static Path fixtureRoot() {
    Path directory = Paths.get("").toAbsolutePath();
    while (directory != null) {
      final Path candidate = directory.resolve(FIXTURE_PATH);
      if (Files.exists(candidate.resolve(CORE.ufcPath))
          && Files.isDirectory(candidate.resolve(CORE.caseDirectory))
          && Files.exists(candidate.resolve(DEPENDENT_FLAGS.ufcPath))
          && Files.isDirectory(candidate.resolve(DEPENDENT_FLAGS.caseDirectory))) {
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

  /** Normalizes integral numbers read as Long to Double, as the UFC parser does. */
  private static Object normalize(final Object value) {
    if (value instanceof Long) {
      return ((Long) value).doubleValue();
    }
    if (value instanceof Map) {
      final Map<String, Object> map = new LinkedHashMap<>();
      ((Map<?, ?>) value).forEach((key, nested) -> map.put((String) key, normalize(nested)));
      return map;
    }
    if (value instanceof List) {
      final List<Object> list = new ArrayList<>();
      ((List<?>) value).forEach(nested -> list.add(normalize(nested)));
      return list;
    }
    return value;
  }

  private static final class CapturingPipeline extends FlagEvaluationPipeline {
    private final List<FlagEvalEvent> events;

    private CapturingPipeline(final List<FlagEvalEvent> events) {
      super(
          1, 1, SECONDS, (route, json) -> {}, emptyMap(), new RuntimeServices(HealthMetrics.NOOP));
      this.events = events;
    }

    @Override
    public boolean hasCapacityForEnqueue() {
      return true;
    }

    @Override
    public void enqueue(final FlagEvalEvent event) {
      events.add(event);
    }
  }

  /** Evaluator with per-case caches and optional in-memory telemetry sinks. */
  private static final class FixtureEvaluator extends DDEvaluator {
    private final ExposureDeduplicationCache cache = new ExposureDeduplicationCache();
    private final List<ExposureEvent> exposures = new ArrayList<>();
    private final FeatureFlagsRuntime runtime;
    private final boolean captureExposures;

    private FixtureEvaluator(
        final ServerConfiguration configuration,
        final ExperimentationOptOutMode mode,
        final FeatureFlagsRuntime runtime,
        final boolean captureExposures) {
      super(
          mock(Runnable.class),
          AgentBridge.NONE,
          TestSettings.of("experimental.flagging.provider.span.enrichment.enabled", "true"),
          mode);
      this.runtime = runtime;
      this.captureExposures = captureExposures;
      accept(configuration);
    }

    @Override
    public boolean initialize(
        final long timeout, final TimeUnit unit, final EvaluationContext context) {
      return hasConfiguration();
    }

    @Override
    FeatureFlagsRuntime runtime() {
      return runtime;
    }

    @Override
    ExposureDeduplicationCache exposureCache() {
      return cache;
    }

    @Override
    boolean isExposureDeliveryEnabled() {
      return captureExposures;
    }

    @Override
    void recordExposure(final ExposureEvent event) {
      exposures.add(event);
    }
  }

  private static final class FixtureSuite {
    final String name;
    final String ufcPath;
    final String caseDirectory;

    private FixtureSuite(final String name, final String ufcPath, final String caseDirectory) {
      this.name = name;
      this.ufcPath = ufcPath;
      this.caseDirectory = caseDirectory;
    }
  }

  static final class FixtureCase {
    final Map<String, Object> json;
    final FixtureSuite suite;
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
    final boolean checksSerialId;
    final Number resultSerialId;
    final Boolean resultDoLog;
    final FixtureExpectations expectations;

    @SuppressWarnings("unchecked")
    FixtureCase(
        final FixtureSuite suite,
        final String fileName,
        final int index,
        final Map<String, Object> json) {
      this.json = json;
      this.suite = suite;
      this.fileName = fileName;
      this.index = index;
      final Object attributes = normalize(json.get("attributes"));
      this.attributes = attributes == null ? emptyMap() : (Map<String, Object>) attributes;
      this.defaultValue = normalize(json.get("defaultValue"));
      this.flag = (String) json.get("flag");
      this.targetingKey = (String) json.get("targetingKey");
      this.variationType = (String) json.get("variationType");
      final Map<String, Object> result = (Map<String, Object>) json.get("result");
      this.checksSerialId = result.containsKey("serialId");
      this.resultSerialId = (Number) result.get("serialId");
      this.resultDoLog = (Boolean) result.get("doLog");
      this.resultValue = normalize(result.get("value"));
      this.resultReason = (String) result.get("reason");
      this.resultErrorCode = (String) result.get("errorCode");
      this.resultVariant = (String) result.get("variant");
      final Map<String, Object> metadata = (Map<String, Object>) result.get("flagMetadata");
      final Object allocationKey = metadata == null ? null : metadata.get("allocationKey");
      this.resultAllocationKey = allocationKey == null ? null : String.valueOf(allocationKey);
      final Map<String, Object> expectations = (Map<String, Object>) json.get("expectations");
      this.expectations = expectations == null ? null : new FixtureExpectations(expectations);
    }

    @Override
    public String toString() {
      return suite.name + "/" + fileName + "[" + index + "] flag=" + flag;
    }
  }

  private static final class FixtureExpectations {
    final List<EventMatcher> exposures;
    final List<EventMatcher> evaluationEvents;
    final boolean noUnmatchedEvents;

    @SuppressWarnings("unchecked")
    private FixtureExpectations(final Map<String, Object> json) {
      this.exposures = matchers((List<Object>) json.get("exposures"));
      this.evaluationEvents = matchers((List<Object>) json.get("evaluationEvents"));
      this.noUnmatchedEvents = Boolean.TRUE.equals(json.get("noUnmatchedEvents"));
    }

    private void assertMatches(
        final List<ExposureEvent> actualExposures,
        final List<FlagEvalEvent> actualEvaluationEvents) {
      assertEvents(exposures, actualExposures, noUnmatchedEvents, "exposure");
      assertEvents(evaluationEvents, actualEvaluationEvents, noUnmatchedEvents, "evaluation");
    }

    private static <T> void assertEvents(
        final List<EventMatcher> expected,
        final List<T> actual,
        final boolean exact,
        final String eventType) {
      final List<T> unconsumed = new ArrayList<>(actual);
      for (final EventMatcher matcher : expected) {
        for (int count = 0; count < matcher.count; count++) {
          boolean matched = false;
          for (int index = 0; index < unconsumed.size(); index++) {
            if (matcher.matches(unconsumed.get(index))) {
              unconsumed.remove(index);
              matched = true;
              break;
            }
          }
          assertTrue(matched, "missing " + eventType + " for " + matcher + "; actual=" + actual);
        }
      }
      if (exact) {
        assertTrue(unconsumed.isEmpty(), "unexpected " + eventType + " events: " + unconsumed);
      }
    }

    @SuppressWarnings("unchecked")
    private static List<EventMatcher> matchers(final List<Object> items) {
      if (items == null) {
        return Collections.emptyList();
      }
      final List<EventMatcher> result = new ArrayList<>();
      for (final Object item : items) {
        final Map<String, Object> matcher = (Map<String, Object>) item;
        if (isApplicable(matcher)) {
          result.add(new EventMatcher(matcher));
        }
      }
      return result;
    }
  }

  private static final class EventMatcher {
    final int count;
    final Map<String, Object> fields;

    private EventMatcher(final Map<String, Object> json) {
      final Object count = json.get("_count");
      this.count = count instanceof Number ? ((Number) count).intValue() : 1;
      this.fields = new LinkedHashMap<>(json);
      this.fields.remove("_count");
      this.fields.remove("_skip");
      this.fields.remove("_include");
    }

    private boolean matches(final Object event) {
      for (final Map.Entry<String, Object> field : fields.entrySet()) {
        final Object actual = actualField(event, field.getKey());
        if (!Objects.equals(field.getValue(), actual)) {
          return false;
        }
      }
      return true;
    }

    private static Object actualField(final Object event, final String field) {
      if (event instanceof ExposureEvent) {
        final ExposureEvent exposure = (ExposureEvent) event;
        switch (field) {
          case "flag":
            return exposure.flag == null ? null : exposure.flag.key;
          case "allocation":
            return exposure.allocation == null ? null : exposure.allocation.key;
          case "variant":
            return exposure.variant == null ? null : exposure.variant.key;
          default:
            throw new IllegalArgumentException("Unsupported exposure field: " + field);
        }
      }
      final FlagEvalEvent evaluation = (FlagEvalEvent) event;
      switch (field) {
        case "flag":
          return evaluation.flagKey;
        case "errorCode":
          return evaluation.errorMessage;
        default:
          throw new IllegalArgumentException("Unsupported evaluation field: " + field);
      }
    }

    @Override
    public String toString() {
      return fields + " x" + count;
    }
  }
}
