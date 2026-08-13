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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ParsedSemver;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DDEvaluatorTest {

  private static final String CANONICAL_FIXTURE_PATH =
      "dd-smoke-tests/openfeature/src/test/resources/ffe-system-test-data";
  private static final Moshi MOSHI =
      new Moshi.Builder().add(Date.class, new DateAdapter()).add(FlagMapAdapter.FACTORY).build();
  private static final JsonAdapter<ServerConfiguration> CONFIG_ADAPTER =
      MOSHI.adapter(ServerConfiguration.class);
  private static final Type FIXTURE_LIST_TYPE =
      Types.newParameterizedType(List.class, FixtureCase.class);
  private static final JsonAdapter<List<FixtureCase>> FIXTURE_LIST_ADAPTER =
      MOSHI.adapter(FIXTURE_LIST_TYPE);
  private static final JsonAdapter<RegexConformanceFixture> REGEX_CONFORMANCE_ADAPTER =
      MOSHI.adapter(RegexConformanceFixture.class);
  private static final ThreadLocal<Map<String, String>> INVALID_FLAGS_HOLDER =
      ThreadLocal.withInitial(HashMap::new);

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
      Arguments.of(Long.class, 42L, IllegalArgumentException.class),
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
    evaluator.accept(new ServerConfiguration("", "", false, null, flags));

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

  // ---- observeFullEvaluationData metadata is stamped from the evaluator's ServerConfiguration
  // ----
  //
  // Every code path that returns a ProviderEvaluation must stamp the consent boolean so downstream
  // hooks can honour it. These tests exercise each stamp site with both consent values (on/off) so
  // a mutation to any stamp — deleting the line, hardcoding the value — flips at least one
  // assertion.

  // -- success path: resolveVariant (variant metadata builder) --

  @Test
  public void observeFullEvaluationDataStampedTrueOnResolvedVariant() {
    final ProviderEvaluation<?> details = evaluateMatchingFlag(true);

    assertThat(details.getReason(), equalTo("STATIC"));
    assertThat(details.getVariant(), equalTo("on"));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(true));
  }

  @Test
  public void observeFullEvaluationDataStampedFalseOnResolvedVariant() {
    // Symmetric consent-off assertion. Paired with the consent-on test above this pins the
    // resolveVariant metadata line (DDEvaluator.java: METADATA_OBSERVE_FULL_EVALUATION_DATA) so
    // deleting it or hardcoding either value would fail at least one assertion.
    final ProviderEvaluation<?> details = evaluateMatchingFlag(false);

    assertThat(details.getReason(), equalTo("STATIC"));
    assertThat(details.getVariant(), equalTo("on"));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(false));
  }

  // -- DISABLED path: flag.enabled=false --

  @Test
  public void observeFullEvaluationDataStampedTrueOnDisabledFlag() {
    final ProviderEvaluation<?> details = evaluateDisabledFlag(true);

    assertThat(details.getReason(), equalTo("DISABLED"));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(true));
  }

  @Test
  public void observeFullEvaluationDataStampedFalseOnDisabledFlag() {
    final ProviderEvaluation<?> details = evaluateDisabledFlag(false);

    assertThat(details.getReason(), equalTo("DISABLED"));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(false));
  }

  // -- DEFAULT path: no allocation matches --

  @Test
  public void observeFullEvaluationDataStampedTrueOnDefault() {
    // Allocation exists but has empty splits, so the loop finishes without returning and we fall
    // through to the DEFAULT branch.
    final ProviderEvaluation<?> details = evaluateWithEmptySplits(true);

    assertThat(details.getReason(), equalTo("DEFAULT"));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(true));
  }

  @Test
  public void observeFullEvaluationDataStampedFalseOnDefault() {
    final ProviderEvaluation<?> details = evaluateWithEmptySplits(false);

    assertThat(details.getReason(), equalTo("DEFAULT"));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(false));
  }

  // -- error paths: FLAG_NOT_FOUND / PROVIDER_NOT_READY (via consentMetadata in error()) --

  @Test
  public void observeFullEvaluationDataStampedOnFlagNotFoundError() {
    // Was previously named "…OnSuccess" but actually exercises the error() helper's stamp via
    // FLAG_NOT_FOUND — kept for that stamp site, correctly named.
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", true, null, new HashMap<>()));

    final EvaluationContext ctx = new MutableContext("target").setTargetingKey("k");
    final ProviderEvaluation<?> details =
        evaluator.evaluate(Integer.class, "unknown-flag", 23, ctx);

    assertThat(details.getErrorCode(), equalTo(ErrorCode.FLAG_NOT_FOUND));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(true));
  }

  @Test
  public void observeFullEvaluationDataDefaultsToFalseWhenEvaluatorHasNoConfig() {
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    final ProviderEvaluation<?> details =
        evaluator.evaluate(Integer.class, "test", 23, mock(EvaluationContext.class));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.PROVIDER_NOT_READY));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(false));
  }

  @Test
  public void observeFullEvaluationDataNullConfigFieldTreatedAsFalse() {
    // The field is boxed so Moshi tolerates a malformed consent value in the UFC JSON without
    // aborting the whole parse. The evaluator must then interpret null as the privacy-preserving
    // default. An auto-unbox at the read site (config.observeFullEvaluationData) would NPE here.
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("target", new Flag("target", true, ValueType.INTEGER, emptyMap(), emptyList()));
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", null, null, flags));

    final EvaluationContext ctx = new MutableContext("target").setTargetingKey("k");
    final ProviderEvaluation<?> details = evaluator.evaluate(Integer.class, "target", 23, ctx);

    // Flags still evaluate — availability preserved despite the malformed consent field.
    assertThat(details.getReason(), equalTo("DEFAULT"));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(false));
  }

  // Builds a flag that reaches resolveVariant: enabled, one allocation with no rules, one split
  // with empty shards (so the shard-match branch is skipped and the split is picked immediately),
  // and a single "on" variant whose value maps to the requested Integer type.
  private static ProviderEvaluation<?> evaluateMatchingFlag(
      final boolean observeFullEvaluationData) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    final Split split = new Split(emptyList(), "on", emptyMap(), null);
    final Allocation allocation =
        new Allocation("alloc-1", null, null, null, singletonList(split), Boolean.FALSE);
    return evaluateFlag(
        new Flag("target", true, ValueType.INTEGER, variations, singletonList(allocation)),
        observeFullEvaluationData);
  }

  private static ProviderEvaluation<?> evaluateDisabledFlag(
      final boolean observeFullEvaluationData) {
    return evaluateFlag(
        new Flag("target", false, ValueType.INTEGER, emptyMap(), null), observeFullEvaluationData);
  }

  private static ProviderEvaluation<?> evaluateWithEmptySplits(
      final boolean observeFullEvaluationData) {
    // Enabled, allocations present, allocation active, no rules, empty splits → falls through the
    // for-loop to the DEFAULT return.
    final Allocation allocation =
        new Allocation("alloc-1", null, null, null, emptyList(), Boolean.FALSE);
    return evaluateFlag(
        new Flag("target", true, ValueType.INTEGER, emptyMap(), singletonList(allocation)),
        observeFullEvaluationData);
  }

  private static ProviderEvaluation<?> evaluateFlag(
      final Flag flag, final boolean observeFullEvaluationData) {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("target", flag);
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", observeFullEvaluationData, null, flags));

    final EvaluationContext ctx = new MutableContext("target").setTargetingKey("user-1");
    return evaluator.evaluate(Integer.class, "target", 23, ctx);
  }

  // ---- error message redaction respects observeFullEvaluationData ----

  @Test
  public void numericConditionOnTargetingKeyDropsExceptionMessageUnderConsentOff() {
    // Rule {attribute:"id", operator:GT, value:0} + "id" not in context →
    // DDEvaluator.resolveAttribute
    // falls back to the targeting key, so Double.parseDouble("jane.doe@datadoghq.com") throws
    // NumberFormatException. The exception message echoes the raw context value verbatim, so it
    // must be dropped when observeFullEvaluationData=false.
    final ProviderEvaluation<?> details =
        evaluateWithNumericRuleOnId("jane.doe@datadoghq.com", false);

    assertThat(details.getErrorCode(), equalTo(ErrorCode.TYPE_MISMATCH));
    assertNull(details.getErrorMessage(), "consent-off must not surface the raw exception message");
  }

  @Test
  public void numericConditionOnTargetingKeyPreservesExceptionMessageUnderConsentOn() {
    // Symmetric case: with consent on, the raw exception message flows through unchanged so
    // operators keep the diagnostic detail they opted in to.
    final ProviderEvaluation<?> details =
        evaluateWithNumericRuleOnId("jane.doe@datadoghq.com", true);

    assertThat(details.getErrorCode(), equalTo(ErrorCode.TYPE_MISMATCH));
    assertThat(details.getErrorMessage(), equalTo("For input string: \"jane.doe@datadoghq.com\""));
  }

  @Test
  public void numericConditionOnTargetingKeyErrorMessageNeverContainsPiiUnderConsentOff() {
    // Belt-and-suspenders: independent of the exact null/empty form, the raw PII value must never
    // appear in the message under consent-off. Guards against future changes that might replace
    // null with a redacted string or a code-name suffix.
    final ProviderEvaluation<?> details =
        evaluateWithNumericRuleOnId("jane.doe@datadoghq.com", false);

    final String message = details.getErrorMessage();
    assertFalse(
        message != null && message.contains("jane.doe@datadoghq.com"),
        "consent-off errorMessage must not contain raw context values");
  }

  private static ProviderEvaluation<?> evaluateWithNumericRuleOnId(
      final String targetingKey, final boolean observeFullEvaluationData) {
    final Map<String, Flag> flags = new HashMap<>();
    final List<Rule> rules =
        singletonList(
            new Rule(singletonList(new ConditionConfiguration(ConditionOperator.GT, "id", 0))));
    // Split must be non-empty so the allocation is considered a match target; its contents don't
    // matter because the rule throws before a split is picked.
    final Allocation allocation =
        new Allocation("alloc", rules, null, null, emptyList(), Boolean.FALSE);
    flags.put(
        "num-rule",
        new Flag("num-rule", true, ValueType.INTEGER, emptyMap(), singletonList(allocation)));
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", observeFullEvaluationData, null, flags));

    final EvaluationContext ctx = new MutableContext(targetingKey);
    return evaluator.evaluate(Integer.class, "num-rule", 23, ctx);
  }

  @Test
  public void testAllocationDateAbiAndInstantAccessors() throws Exception {
    final Date startAt = Date.from(Instant.parse("2024-01-01T00:00:00Z"));
    final Date endAt = Date.from(Instant.parse("2024-12-31T23:59:59Z"));
    final Allocation allocation =
        new Allocation("allocation", emptyList(), startAt, endAt, emptyList(), true);

    assertThat(Allocation.class.getField("startAt").getType(), equalTo(Date.class));
    assertThat(Allocation.class.getField("endAt").getType(), equalTo(Date.class));
    assertThat(allocation.startAtInstant(), equalTo(startAt.toInstant()));
    assertThat(allocation.endAtInstant(), equalTo(endAt.toInstant()));
  }

  @Test
  public void testAllocationWindowHonorsMicrosecondPrecision() {
    final Instant startAt = Instant.parse("2024-01-01T00:00:00.123456Z");
    final Instant endAt = Instant.parse("2024-01-01T00:00:00.987654Z");
    final Allocation allocation =
        Allocation.fromInstants("allocation", emptyList(), startAt, endAt, emptyList(), true);

    assertThat(
        DDEvaluator.isAllocationActive(allocation, startAt.minusNanos(1_000)), equalTo(false));
    assertThat(DDEvaluator.isAllocationActive(allocation, startAt), equalTo(true));
    assertThat(DDEvaluator.isAllocationActive(allocation, endAt), equalTo(true));
    assertThat(DDEvaluator.isAllocationActive(allocation, endAt.plusNanos(1_000)), equalTo(false));
  }

  // --- SemVer condition evaluation tests (ported from Go evaluator_test.go) ---

  private static Flag semverFlag(final ConditionOperator operator, final String comparand) {
    final ParsedSemver parsed = ParsedSemver.parse(comparand);
    final ConditionConfiguration condition =
        new ConditionConfiguration(operator, "version", comparand);
    condition.semverComparand = parsed;
    final Rule rule = new Rule(singletonList(condition));
    final Split split = new Split(emptyList(), "on", null, null);
    final Allocation allocation =
        Allocation.fromInstants(
            "targeted", singletonList(rule), null, null, singletonList(split), false);
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", true));
    return new Flag("test-flag", true, ValueType.BOOLEAN, variations, singletonList(allocation));
  }

  private static EvaluationContext semverContext(final Object version) {
    final Map<String, Object> attributes = new HashMap<>();
    if (version != null) {
      attributes.put("version", version);
    }
    final MutableContext context =
        new MutableContext(Value.objectToValue(attributes).asStructure().asMap());
    context.setTargetingKey("subject");
    return context;
  }

  static Arguments[] semverConditionTestCases() {
    return new Arguments[] {
      // Equal
      Arguments.of(ConditionOperator.SEMVER_EQ, "1.2.3", "1.2.3", true),
      Arguments.of(ConditionOperator.SEMVER_EQ, "1.2.4", "1.2.3", false),
      // Not equal
      Arguments.of(ConditionOperator.SEMVER_NEQ, "1.2.4", "1.2.3", true),
      Arguments.of(ConditionOperator.SEMVER_NEQ, "1.2.3", "1.2.3", false),
      // Less than
      Arguments.of(ConditionOperator.SEMVER_LT, "1.9.9", "2.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_LT, "2.0.0", "2.0.0", false),
      // Less than or equal
      Arguments.of(ConditionOperator.SEMVER_LTE, "2.0.0", "2.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_LTE, "2.0.1", "2.0.0", false),
      // Greater than
      Arguments.of(ConditionOperator.SEMVER_GT, "1.0.1", "1.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_GT, "1.0.0", "1.0.0", false),
      // Greater than or equal
      Arguments.of(ConditionOperator.SEMVER_GTE, "1.0.0", "1.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_GTE, "0.9.9", "1.0.0", false),
      // Prerelease ordering
      Arguments.of(ConditionOperator.SEMVER_LT, "1.0.0-beta.1", "1.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_LT, "1.0.0-beta.2", "1.0.0-beta.11", true),
      // Build metadata is ignored
      Arguments.of(ConditionOperator.SEMVER_EQ, "4.0.0+build.42", "4.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_EQ, "4.0.0+exp.sha.5114f85", "4.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_NEQ, "4.0.0+build.42", "4.0.0", false),
      Arguments.of(ConditionOperator.SEMVER_LT, "4.0.0+build.42", "4.0.0", false),
      Arguments.of(ConditionOperator.SEMVER_LTE, "4.0.0+build.42", "4.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_GT, "4.0.0+build.42", "4.0.0", false),
      Arguments.of(ConditionOperator.SEMVER_GTE, "4.0.0+build.42", "4.0.0", true),
      Arguments.of(ConditionOperator.SEMVER_EQ, "1.0.0+linux", "1.0.0+darwin", true),
      // Invalid attribute does not match
      Arguments.of(ConditionOperator.SEMVER_NEQ, "not-a-version", "1.0.0", false),
      Arguments.of(ConditionOperator.SEMVER_GTE, "1.2", "1.0.0", false),
      Arguments.of(ConditionOperator.SEMVER_GTE, "v1.2.3", "1.0.0", false),
      Arguments.of(ConditionOperator.SEMVER_GTE, "18446744073709551616.0.0", "1.0.0", false),
      // Non-string attribute does not match
      Arguments.of(ConditionOperator.SEMVER_EQ, 1.2, "1.2.0", false),
    };
  }

  @ParameterizedTest(name = "{0} attr={1} comparand={2} -> {3}")
  @MethodSource("semverConditionTestCases")
  public void testEvaluateSemverCondition(
      final ConditionOperator operator,
      final Object attribute,
      final String comparand,
      final boolean wantMatch) {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("test-flag", semverFlag(operator, comparand));
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", null, null, flags));

    final ProviderEvaluation<Boolean> details =
        evaluator.evaluate(Boolean.class, "test-flag", false, semverContext(attribute));

    if (wantMatch) {
      assertThat(details.getValue(), equalTo(true));
      assertThat(details.getReason(), equalTo("TARGETING_MATCH"));
    } else {
      assertThat(details.getValue(), equalTo(false));
      assertThat(details.getReason(), equalTo("DEFAULT"));
    }
  }

  @Test
  public void testEvaluateSemverConditionMissingAttribute() {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("test-flag", semverFlag(ConditionOperator.SEMVER_EQ, "1.2.3"));
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", null, null, flags));

    final ProviderEvaluation<Boolean> details =
        evaluator.evaluate(Boolean.class, "test-flag", false, semverContext(null));

    assertThat(details.getValue(), equalTo(false));
    assertThat(details.getReason(), equalTo("DEFAULT"));
  }

  @Test
  public void testEvaluateSemverConditionInvalidComparandReturnsParseError() {
    // A flag with an invalid semver comparand is dropped during parsing.
    // The evaluator should return PARSE_ERROR when the flag is queried.
    final Map<String, Flag> flags = new HashMap<>();
    final Map<String, String> invalidFlags = new HashMap<>();
    invalidFlags.put("invalid-semver", "invalid_semver_comparand");
    final ServerConfiguration config = new ServerConfiguration("", "", null, null, flags);
    config.invalidFlags = invalidFlags;
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(config);

    final ProviderEvaluation<Boolean> details =
        evaluator.evaluate(Boolean.class, "invalid-semver", false, semverContext("1.2.3"));

    assertThat(details.getValue(), equalTo(false));
    assertThat(details.getReason(), equalTo(ERROR.name()));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.PARSE_ERROR));
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
    arguments.add(
        Arguments.of(
            mapOf("plan", "gold", "cohort", "gold"), mapOf("plan", "gold", "cohort", "gold")));
    final Instant instant = Instant.parse("2026-07-10T12:34:56Z");
    arguments.add(Arguments.of(mapOf("instant", instant), mapOf("instant", instant.toString())));
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
  public void testDeeplyNestedContextIsTruncatedRatherThanOverflowingTheStack() {
    Value nested = new Value("leaf");
    for (int i = 0; i < 10_000; i++) {
      nested = new Value(singletonList(nested));
    }
    final EvaluationContext context = new MutableContext().add("deep", singletonList(nested));

    final Map<String, Object> result = DDEvaluator.flattenContext(context);

    final StringBuilder truncatedKey = new StringBuilder("deep");
    for (int i = 0; i < DDEvaluator.MAX_SNAPSHOT_DEPTH; i++) {
      truncatedKey.append("[0]");
    }
    assertThat(result.size(), equalTo(1));
    assertThat(result, hasEntry(truncatedKey.toString(), null));
  }

  @Test
  public void testCopyPrunedContextCapsTopLevelFieldCount() {
    final MutableContext context = new MutableContext();
    for (int i = 0; i < DDEvaluator.MAX_CONTEXT_FIELDS + 100; i++) {
      context.add(String.format("k%04d", i), "v");
    }

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    assertThat(result.attrs.size(), equalTo(DDEvaluator.MAX_CONTEXT_FIELDS));
    assertThat(result.truncatedReason, equalTo("max_context_fields"));
  }

  @Test
  public void testCopyPrunedContextSkipsOversizedStringValues() {
    final char[] longChars = new char[DDEvaluator.MAX_VALUE_LENGTH + 1];
    java.util.Arrays.fill(longChars, 'x');
    final MutableContext context = new MutableContext();
    context.add("keep", "ok");
    context.add("drop", new String(longChars));

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    assertThat(result.attrs, hasEntry("keep", "ok"));
    assertThat(result.attrs.containsKey("drop"), equalTo(false));
    assertThat(result.truncatedReason, equalTo("max_value_length"));
  }

  @Test
  public void testCopyPrunedContextSkipsOversizedKeys() {
    final char[] longKeyChars = new char[DDEvaluator.MAX_KEY_LENGTH + 1];
    java.util.Arrays.fill(longKeyChars, 'k');
    final MutableContext context = new MutableContext();
    context.add("keep", "ok");
    context.add(new String(longKeyChars), "drop");

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    assertThat(result.attrs, hasEntry("keep", "ok"));
    assertThat(result.attrs.size(), equalTo(1));
    assertThat(result.truncatedReason, equalTo("max_key_length"));
  }

  @Test
  public void testCopyPrunedContextCapsListWidth() {
    final List<Value> wide = new java.util.ArrayList<>();
    for (int i = 0; i < DDEvaluator.MAX_LIST_ELEMENTS + 50; i++) {
      wide.add(Value.objectToValue("v" + i));
    }
    final EvaluationContext context = new MutableContext().add("list", wide);

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    assertThat(result.attrs.containsKey("list[0]"), equalTo(true));
    assertThat(
        result.attrs.containsKey("list[" + (DDEvaluator.MAX_LIST_ELEMENTS - 1) + "]"),
        equalTo(true));
    assertThat(
        result.attrs.containsKey("list[" + DDEvaluator.MAX_LIST_ELEMENTS + "]"), equalTo(false));
    assertThat(result.truncatedReason, equalTo("max_list_elements"));
  }

  @Test
  public void testCopyPrunedContextCapsStructureWidth() {
    final dev.openfeature.sdk.MutableStructure wide = new dev.openfeature.sdk.MutableStructure();
    for (int i = 0; i < DDEvaluator.MAX_STRUCTURE_PROPERTIES + 50; i++) {
      wide.add(String.format("p%04d", i), "v");
    }
    final EvaluationContext context = new MutableContext().add("struct", wide);

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    long structKeys = result.attrs.keySet().stream().filter(k -> k.startsWith("struct.")).count();
    assertThat(structKeys, equalTo((long) DDEvaluator.MAX_STRUCTURE_PROPERTIES));
    assertThat(result.truncatedReason, equalTo("max_structure_properties"));
  }

  @Test
  public void testCopyPrunedContextTruncatesDeepNesting() {
    Value nested = new Value("leaf");
    for (int i = 0; i < 10_000; i++) {
      nested = new Value(singletonList(nested));
    }
    final EvaluationContext context = new MutableContext().add("deep", singletonList(nested));

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    final StringBuilder truncatedKey = new StringBuilder("deep");
    for (int i = 0; i < DDEvaluator.MAX_SNAPSHOT_DEPTH; i++) {
      truncatedKey.append("[0]");
    }
    // The recursion stops on the first list element at MAX_SNAPSHOT_DEPTH; deeper elements are
    // never walked and no entry is emitted for them.
    assertThat(result.attrs.containsKey(truncatedKey.toString()), equalTo(false));
    assertThat(result.attrs.size(), equalTo(0));
    assertThat(result.truncatedReason, equalTo("max_snapshot_depth"));
  }

  @Test
  public void testCopyPrunedContextExcludesTargetingKey() {
    final MutableContext context = new MutableContext("user-42").add("region", "us-east-1");

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    assertThat(result.attrs, hasEntry("region", "us-east-1"));
    assertThat(result.attrs.containsKey("targetingKey"), equalTo(false));
    assertThat(result.truncatedReason, equalTo(null));
  }

  @Test
  public void testCopyPrunedContextNoTruncationReturnsNullReason() {
    final MutableContext context = new MutableContext("user-1");
    context.add("region", "us-east-1");
    context.add("tier", "gold");

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    assertThat(result.attrs, hasEntry("region", "us-east-1"));
    assertThat(result.truncatedReason, equalTo(null));
  }

  @Test
  public void testCopyPrunedContextMultipleReasonsAreSortedAndDeduplicated() {
    final char[] longChars = new char[DDEvaluator.MAX_VALUE_LENGTH + 1];
    java.util.Arrays.fill(longChars, 'x');
    final char[] longKeyChars = new char[DDEvaluator.MAX_KEY_LENGTH + 1];
    java.util.Arrays.fill(longKeyChars, 'k');
    final MutableContext context = new MutableContext();
    context.add("keep", "ok");
    context.add("dropValue", new String(longChars));
    context.add(new String(longKeyChars), "dropKey");

    final DDEvaluator.CopyResult result = DDEvaluator.copyPrunedContext(context);

    // Both max_key_length and max_value_length fired; sorted alphabetically, no duplicates.
    assertThat(result.truncatedReason, equalTo("max_key_length,max_value_length"));
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

  @MethodSource("regexConformanceCases")
  @ParameterizedTest(name = "{0}")
  public void testRegexConformance(final RegexConformanceCase testCase) {
    boolean compiled = true;
    boolean matched = false;
    try {
      // The Java evaluator consumes the raw pattern from UFC without a normalization step.
      matched = DDEvaluator.matchesRegex(testCase.input, testCase.rawPattern);
    } catch (final PatternSyntaxException ignored) {
      compiled = false;
    }

    if ("accepted".equals(testCase.contract)) {
      assertThat(testCase.id + " compile", compiled, equalTo(true));
      assertThat(testCase.id + " match", matched, equalTo(testCase.expectedMatch));
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
    INVALID_FLAGS_HOLDER.get().clear();
    try {
      final ServerConfiguration config =
          CONFIG_ADAPTER.fromJson(read(fixtureRoot().resolve("ufc-config.json")));
      final Map<String, String> invalidFlags = new HashMap<>(INVALID_FLAGS_HOLDER.get());
      if (!invalidFlags.isEmpty()) {
        config.invalidFlags = invalidFlags;
      }
      validateAndCacheSemverComparands(config);
      return config;
    } finally {
      INVALID_FLAGS_HOLDER.get().clear();
    }
  }

  /**
   * Validates and caches SemVer comparands for all SEMVER_* conditions in the configuration. Flags
   * with invalid comparands are removed from the flags map and tracked in invalidFlags, matching
   * the behavior of {@link com.datadog.featureflag.UniversalFlagConfigParser}.
   */
  private static void validateAndCacheSemverComparands(final ServerConfiguration config) {
    if (config.flags == null) {
      return;
    }
    final Map<String, String> invalidFlags =
        config.invalidFlags == null ? new HashMap<>() : new HashMap<>(config.invalidFlags);
    final Map<String, Flag> flagsToRemove = new HashMap<>();
    for (final Map.Entry<String, Flag> entry : config.flags.entrySet()) {
      final String flagKey = entry.getKey();
      final Flag flag = entry.getValue();
      if (flag.allocations == null) {
        continue;
      }
      boolean invalid = false;
      for (final Allocation allocation : flag.allocations) {
        if (allocation.rules == null) {
          continue;
        }
        for (final Rule rule : allocation.rules) {
          if (rule.conditions == null) {
            continue;
          }
          for (final ConditionConfiguration condition : rule.conditions) {
            if (condition.operator == null) {
              continue;
            }
            switch (condition.operator) {
              case SEMVER_EQ:
              case SEMVER_NEQ:
              case SEMVER_LT:
              case SEMVER_LTE:
              case SEMVER_GT:
              case SEMVER_GTE:
                if (!(condition.value instanceof String)) {
                  invalid = true;
                  break;
                }
                final ParsedSemver parsed = ParsedSemver.parse((String) condition.value);
                if (parsed == null) {
                  invalid = true;
                  break;
                }
                condition.semverComparand = parsed;
                break;
              default:
                break;
            }
          }
        }
      }
      if (invalid) {
        flagsToRemove.put(flagKey, flag);
        invalidFlags.put(flagKey, "invalid_semver_comparand");
      }
    }
    for (final String flagKey : flagsToRemove.keySet()) {
      config.flags.remove(flagKey);
    }
    if (!invalidFlags.isEmpty()) {
      config.invalidFlags = invalidFlags;
    }
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

  private static List<RegexConformanceCase> regexConformanceCases() throws IOException {
    final Path fixtureFile =
        fixtureRoot().resolve("regex-conformance/targeting-regex-conformance.json");
    final RegexConformanceFixture fixture = REGEX_CONFORMANCE_ADAPTER.fromJson(read(fixtureFile));
    if (fixture == null || fixture.cases == null) {
      throw new JsonDataException(
          "Regex conformance fixture did not contain cases: " + fixtureFile);
    }
    if (!"datadog.ffe.targeting-regex-conformance/v1".equals(fixture.schema)
        || fixture.schemaVersion != 1
        || !"targeting-regex-v2".equals(fixture.contractVersion)) {
      throw new JsonDataException(
          "Unsupported regex conformance fixture: "
              + fixture.schema
              + " v"
              + fixture.schemaVersion
              + " contract="
              + fixture.contractVersion);
    }
    if (fixture.cases.size() != 75
        || fixture.cases.stream().map(testCase -> testCase.id).distinct().count() != 75) {
      throw new JsonDataException(
          "Regex conformance fixture must contain 75 cases with unique IDs");
    }

    assertThat(
        fixture.cases.stream().filter(testCase -> "accepted".equals(testCase.contract)).count(),
        equalTo(30L));
    assertThat(
        fixture.cases.stream().filter(testCase -> "rejected".equals(testCase.contract)).count(),
        equalTo(45L));
    return fixture.cases;
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

  private static final class RegexConformanceFixture {
    String schema;
    int schemaVersion;
    String contractVersion;
    List<RegexConformanceCase> cases;
  }

  private static final class RegexConformanceCase {
    String contract;
    Boolean expectedCompile;
    Boolean expectedMatch;
    String id;
    String input;
    String rawPattern;

    @Override
    public String toString() {
      return id;
    }
  }

  /** Reads the flags map with per-flag failure isolation, matching the production parser. */
  private static final class FlagMapAdapter extends JsonAdapter<Map<String, Flag>> {
    private static final Type FLAGS_TYPE =
        Types.newParameterizedType(Map.class, String.class, Flag.class);

    private static final JsonAdapter.Factory FACTORY =
        (type, annotations, moshi) -> {
          if (!annotations.isEmpty() || !Types.equals(type, FLAGS_TYPE)) {
            return null;
          }
          return new FlagMapAdapter(moshi.adapter(Flag.class));
        };

    private final JsonAdapter<Flag> flagAdapter;

    private FlagMapAdapter(final JsonAdapter<Flag> flagAdapter) {
      this.flagAdapter = flagAdapter;
    }

    @Override
    public Map<String, Flag> fromJson(final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      final Map<String, Flag> flags = new HashMap<>();
      reader.beginObject();
      while (reader.hasNext()) {
        final String flagKey = reader.nextName();
        final Object rawFlag = reader.readJsonValue();
        try {
          final Flag flag = flagAdapter.fromJsonValue(rawFlag);
          if (flag != null) {
            validateFlag(flagKey, flag);
            flags.put(flagKey, flag);
          }
        } catch (JsonDataException | IllegalArgumentException ignored) {
          INVALID_FLAGS_HOLDER.get().put(flagKey, "invalid_flag");
          // A malformed flag must not prevent valid flags in the same configuration from loading.
        }
      }
      reader.endObject();
      return flags;
    }

    private static void validateFlag(final String flagKey, final Flag flag) {
      if (flag.allocations == null) {
        return;
      }
      for (final Allocation allocation : flag.allocations) {
        if (allocation == null || allocation.splits == null) {
          continue;
        }
        for (final Split split : allocation.splits) {
          if (split != null && split.shards == null) {
            throw new IllegalArgumentException(
                "flag \"" + flagKey + "\" contains a split with missing shards");
          }
        }
      }
    }

    @Override
    public void toJson(final JsonWriter writer, final Map<String, Flag> value) {
      throw new UnsupportedOperationException("Reading only adapter");
    }
  }

  private static final class DateAdapter extends JsonAdapter<Date> {
    @Override
    public Date fromJson(final JsonReader reader) throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      try {
        return Date.from(
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(reader.nextString(), Instant::from));
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
