package com.datadog.openfeature;

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
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.openfeature.internal.LocalHttpServer;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.connector.Connector;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.ConditionOperator;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ParsedSemver;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Shard;
import com.datadog.openfeature.internal.ufc.ShardRange;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DDEvaluatorTest {

  private static DDEvaluator newEvaluator(final Runnable configCallback) {
    return new DDEvaluator(configCallback, Connector.NONE, TestSettings.of());
  }

  /** Creates an evaluator whose runtime polls a local CDN that has no configuration. */
  private static DDEvaluator newInitializableEvaluator(
      final Runnable configCallback, final LocalHttpServer cdn) {
    return new DDEvaluator(
        configCallback,
        Connector.NONE,
        TestSettings.of(
            "feature.flags.configuration.source.agentless.base.url",
            cdn.uri("/").toString(),
            "flagging.evaluation.counts.enabled",
            "false"));
  }

  private static final long MAX_UNSIGNED_INT = 0xffff_ffffL;

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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
    final ProviderEvaluation<?> details =
        evaluator.evaluate(Integer.class, "test", 23, mock(EvaluationContext.class));
    assertThat(details.getValue(), equalTo(23));
    assertThat(details.getReason(), equalTo(ERROR.name()));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.PROVIDER_NOT_READY));
  }

  @Test
  public void testInitializeTimesOutWithoutConfig() throws Exception {
    final Runnable configCallback = mock(Runnable.class);
    try (LocalHttpServer cdn = new LocalHttpServer()) {
      final DDEvaluator evaluator = newInitializableEvaluator(configCallback, cdn);
      evaluator.accept(null);
      try {
        assertThat(
            evaluator.initialize(10, MILLISECONDS, mock(EvaluationContext.class)), equalTo(false));
        verify(configCallback, times(0)).run();
      } finally {
        evaluator.shutdown();
      }
    }
  }

  @Test
  public void testInitializeWaitsForNonNullConfig() throws Exception {
    try (LocalHttpServer cdn = new LocalHttpServer()) {
      final DDEvaluator evaluator = newInitializableEvaluator(mock(Runnable.class), cdn);
      final ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        final Future<Boolean> initialized =
            executor.submit(() -> evaluator.initialize(5, SECONDS, mock(EvaluationContext.class)));

        evaluator.accept(null);
        assertThat(initialized.isDone(), equalTo(false));

        evaluator.accept(new ServerConfiguration("", "", false, null, emptyMap()));
        assertThat(initialized.get(5, SECONDS), equalTo(true));
      } finally {
        executor.shutdownNow();
        evaluator.shutdown();
      }
    }
  }

  @Test
  public void testEvaluateNoContext() {
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", false, null, emptyMap()));
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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
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

  @Test
  public void testEvaluateUnsignedShardRange() {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    // The selected shard is above Integer.MAX_VALUE, so this test proves that evaluation uses
    // unsigned 32-bit semantics.
    final Shard shard =
        new Shard(
            "salt",
            singletonList(new ShardRange(3_699_531_192L, 3_699_531_193L)),
            MAX_UNSIGNED_INT);
    final Split split = new Split(singletonList(shard), "on", emptyMap(), null);
    final Allocation allocation =
        new Allocation("alloc-1", null, null, null, singletonList(split), Boolean.FALSE);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "target",
        new Flag("target", true, ValueType.INTEGER, variations, singletonList(allocation)));
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", false, null, flags));

    final EvaluationContext context =
        new MutableContext("target").setTargetingKey("high-shard-user");
    final ProviderEvaluation<?> details = evaluator.evaluate(Integer.class, "target", 23, context);

    assertThat(details.getValue(), equalTo(1));
    assertThat(details.getReason(), equalTo("SPLIT"));
    assertThat(details.getVariant(), equalTo("on"));
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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
    final ProviderEvaluation<?> details =
        evaluator.evaluate(Integer.class, "test", 23, mock(EvaluationContext.class));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.PROVIDER_NOT_READY));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(false));
  }

  @Test
  public void observeFullEvaluationDataNullConfigFieldTreatedAsFalse() {
    // The field is boxed so the parser tolerates a malformed consent value in the UFC JSON without
    // aborting the whole parse. The evaluator must then interpret null as the privacy-preserving
    // default. An auto-unbox at the read site (config.observeFullEvaluationData) would NPE here.
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("target", new Flag("target", true, ValueType.INTEGER, emptyMap(), emptyList()));
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", null, null, flags));

    final EvaluationContext ctx = new MutableContext("target").setTargetingKey("k");
    final ProviderEvaluation<?> details = evaluator.evaluate(Integer.class, "target", 23, ctx);

    // Flags still evaluate — availability preserved despite the malformed consent field.
    assertThat(details.getReason(), equalTo("DEFAULT"));
    assertThat(
        details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_OBSERVE_FULL_EVALUATION_DATA),
        equalTo(false));
  }

  // ---- exposure events carry the split's serial id ----

  @Test
  public void exposureCarriesTheSplitSerialId() {
    assertEquals(Integer.valueOf(340132), exposureFor(340132).serial_id);
  }

  @Test
  public void exposureCarriesSerialIdZero() {
    assertEquals(Integer.valueOf(0), exposureFor(0).serial_id);
  }

  @Test
  public void exposureOmitsSerialIdWhenTheSplitHasNone() {
    assertNull(exposureFor(null).serial_id);
  }

  /**
   * Evaluates a logging allocation whose split carries the given serial id and returns the single
   * dispatched exposure. Span enrichment is off here, as it is by default, so this also pins that
   * the serial id does not travel via the enrichment-gated evaluation metadata.
   */
  private static ExposureEvent exposureFor(final Integer serialId) {
    final List<ExposureEvent> dispatched = new ArrayList<>();
    final DDEvaluator evaluator =
        new DDEvaluator(mock(Runnable.class), Connector.NONE, TestSettings.of()) {
          @Override
          void recordExposure(final ExposureEvent event) {
            dispatched.add(event);
          }
        };
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    final Split split = new Split(emptyList(), "on", emptyMap(), serialId);
    final Allocation allocation =
        new Allocation("alloc-1", null, null, null, singletonList(split), Boolean.TRUE);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "target",
        new Flag("target", true, ValueType.INTEGER, variations, singletonList(allocation)));
    evaluator.accept(new ServerConfiguration("", "", true, null, flags));
    evaluator.evaluate(
        Integer.class, "target", 23, new MutableContext("target").setTargetingKey("user-1"));
    assertEquals(1, dispatched.size());
    return dispatched.get(0);
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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
    evaluator.accept(new ServerConfiguration("", "", observeFullEvaluationData, null, flags));

    final EvaluationContext ctx = new MutableContext(targetingKey);
    return evaluator.evaluate(Integer.class, "num-rule", 23, ctx);
  }

  @Test
  public void testAllocationWindowHonorsMicrosecondPrecision() {
    final Instant startAt = Instant.parse("2024-01-01T00:00:00.123456Z");
    final Instant endAt = Instant.parse("2024-01-01T00:00:00.987654Z");
    final Allocation allocation =
        new Allocation("allocation", emptyList(), startAt, endAt, emptyList(), true);

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
        new Allocation("targeted", singletonList(rule), null, null, singletonList(split), false);
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
      Arguments.of(ConditionOperator.SEMVER_EQ, "1.2.3.4.5.6", "1.2.3.4.5.6", true),
      Arguments.of(ConditionOperator.SEMVER_GT, "1.2.3.4.5.7", "1.2.3.4.5.6", true),
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
      Arguments.of(ConditionOperator.SEMVER_GTE, "1.2", "1.0.0", true),
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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
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
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
    evaluator.accept(config);

    final ProviderEvaluation<Boolean> details =
        evaluator.evaluate(Boolean.class, "invalid-semver", false, semverContext("1.2.3"));

    assertThat(details.getValue(), equalTo(false));
    assertThat(details.getReason(), equalTo(ERROR.name()));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.PARSE_ERROR));
  }

  @Test
  public void testEvaluateMalformedFlagReturnsParseError() {
    final Map<String, Flag> flags = new HashMap<>();
    final Map<String, String> invalidFlags = new HashMap<>();
    invalidFlags.put("malformed-flag", "invalid_flag");
    final ServerConfiguration config = new ServerConfiguration("", "", null, null, flags);
    config.invalidFlags = invalidFlags;
    final DDEvaluator evaluator = newEvaluator(mock(Runnable.class));
    evaluator.accept(config);

    final ProviderEvaluation<Boolean> details =
        evaluator.evaluate(Boolean.class, "malformed-flag", false, new MutableContext());

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

  private static Arguments[] typeCompatibilityTestCases() {
    return new Arguments[] {
      Arguments.of(Boolean.class, ValueType.BOOLEAN, true),
      Arguments.of(String.class, ValueType.BOOLEAN, false),
      Arguments.of(String.class, ValueType.STRING, true),
      Arguments.of(Boolean.class, ValueType.STRING, false),
      Arguments.of(Integer.class, ValueType.INTEGER, true),
      Arguments.of(String.class, ValueType.INTEGER, false),
      Arguments.of(Double.class, ValueType.NUMERIC, true),
      Arguments.of(String.class, ValueType.NUMERIC, false),
      Arguments.of(Value.class, ValueType.JSON, true),
      Arguments.of(String.class, ValueType.JSON, false),
      Arguments.of(String.class, null, true),
    };
  }

  @ParameterizedTest
  @MethodSource("typeCompatibilityTestCases")
  public void testTypeCompatibility(
      final Class<?> target, final ValueType variationType, final boolean expected) {
    assertThat(DDEvaluator.isTypeCompatible(target, variationType), equalTo(expected));
  }

  @Test
  public void testContextCopyDetectsContainerCycles() {
    final List<Value> cyclicList = new ArrayList<>();
    final Value listValue = new Value(cyclicList);
    cyclicList.add(listValue);
    final EvaluationContext listContext = mock(EvaluationContext.class);
    when(listContext.keySet()).thenReturn(java.util.Collections.singleton("list"));
    when(listContext.getValue("list")).thenReturn(listValue);

    final DDEvaluator.CopyResult listResult = DDEvaluator.copyPrunedContext(listContext);
    assertThat(listResult.truncatedReason, equalTo("cycle"));

    final dev.openfeature.sdk.MutableStructure cyclicStructure =
        new dev.openfeature.sdk.MutableStructure();
    final Value structureValue = new Value(cyclicStructure);
    cyclicStructure.add("self", structureValue);
    final EvaluationContext structureContext = mock(EvaluationContext.class);
    when(structureContext.keySet()).thenReturn(java.util.Collections.singleton("structure"));
    when(structureContext.getValue("structure")).thenReturn(structureValue);

    final DDEvaluator.CopyResult structureResult = DDEvaluator.copyPrunedContext(structureContext);
    assertThat(structureResult.truncatedReason, equalTo("cycle"));
  }

  @Test
  public void testCopyPrunedContextHandlesEmptyAndScalarValues() {
    assertThat(DDEvaluator.copyPrunedContext(null).attrs, equalTo(emptyMap()));
    assertThat(DDEvaluator.copyPrunedContext(new MutableContext()).attrs, equalTo(emptyMap()));

    final EvaluationContext nullContext = mock(EvaluationContext.class);
    when(nullContext.keySet())
        .thenReturn(new java.util.LinkedHashSet<>(asList("java-null", "openfeature-null")));
    when(nullContext.getValue("java-null")).thenReturn(null);
    when(nullContext.getValue("openfeature-null")).thenReturn(new Value());
    final Map<String, Value> snapshot = DDEvaluator.snapshotValues(nullContext);
    assertNull(snapshot.get("java-null"));
    assertThat(snapshot.get("openfeature-null").isNull(), equalTo(true));
    final DDEvaluator.CopyResult nullResult = DDEvaluator.copyPrunedContext(nullContext);
    assertThat(nullResult.attrs, hasEntry("java-null", null));
    assertThat(nullResult.attrs, hasEntry("openfeature-null", null));

    final MutableContext scalarContext = new MutableContext();
    scalarContext.add("boolean", true);
    scalarContext.add("number", 42);
    scalarContext.add("instant", Instant.parse("2026-08-20T00:00:00Z"));
    final DDEvaluator.CopyResult scalarResult = DDEvaluator.copyPrunedContext(scalarContext);
    assertThat(scalarResult.attrs, hasEntry("boolean", true));
    assertThat(scalarResult.attrs, hasEntry("number", 42));
    assertThat(scalarResult.attrs, hasEntry("instant", "2026-08-20T00:00:00Z"));
  }

  @Test
  public void testFlattenValuesHandlesJavaNullAndContainerCycles() {
    final Map<String, Value> values = new HashMap<>();
    values.put("null", null);

    final List<Value> cyclicList = new ArrayList<>();
    final Value listValue = new Value(cyclicList);
    cyclicList.add(listValue);
    values.put("list", listValue);

    final dev.openfeature.sdk.MutableStructure cyclicStructure =
        new dev.openfeature.sdk.MutableStructure();
    final Value structureValue = new Value(cyclicStructure);
    cyclicStructure.add("self", structureValue);
    values.put("structure", structureValue);

    final Map<String, Object> flattened = DDEvaluator.flattenValues(values);
    assertThat(flattened, hasEntry("null", null));
    assertThat(flattened.size(), equalTo(1));
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
}
