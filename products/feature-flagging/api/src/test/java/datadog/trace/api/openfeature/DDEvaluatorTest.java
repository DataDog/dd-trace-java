package datadog.trace.api.openfeature;

import static dev.openfeature.sdk.ErrorCode.FLAG_NOT_FOUND;
import static dev.openfeature.sdk.ErrorCode.TARGETING_KEY_MISSING;
import static dev.openfeature.sdk.Reason.DEFAULT;
import static dev.openfeature.sdk.Reason.DISABLED;
import static dev.openfeature.sdk.Reason.ERROR;
import static dev.openfeature.sdk.Reason.TARGETING_MATCH;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Shard;
import datadog.trace.api.featureflag.ufc.v1.ShardRange;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import datadog.trace.api.openfeature.util.TestCase;
import datadog.trace.api.openfeature.util.TestCase.Result;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DDEvaluatorTest {

  @Captor private ArgumentCaptor<ExposureEvent> exposureEventCaptor;

  private FeatureFlaggingGateway.ExposureListener exposureListener;

  @BeforeEach
  public void setup() {
    exposureListener = mock(FeatureFlaggingGateway.ExposureListener.class);
    FeatureFlaggingGateway.addExposureListener(exposureListener);
  }

  @AfterEach
  public void tearDown() {
    FeatureFlaggingGateway.removeExposureListener(exposureListener);
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
    assertThat(details.getReason(), equalTo(ERROR.name()));
    assertThat(details.getErrorCode(), equalTo(ErrorCode.GENERAL));
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

  private static List<TestCase<?>> evaluateTestCases() {
    return Arrays.asList(
        new TestCase<>("default")
            .flag("simple-string")
            .result(new Result<>("default").reason(ERROR.name()).errorCode(TARGETING_KEY_MISSING)),
        new TestCase<>("default")
            .flag("non-existent-flag")
            .targetingKey("user-123")
            .result(new Result<>("default").reason(ERROR.name()).errorCode(FLAG_NOT_FOUND)),
        new TestCase<>("default")
            .flag("disabled-flag")
            .targetingKey("user-123")
            .result(new Result<>("default").reason(DISABLED.name())),
        new TestCase<>("default")
            .flag("simple-string")
            .targetingKey("user-123")
            .result(new Result<>("test-value").reason(TARGETING_MATCH.name()).variant("on")),
        new TestCase<>(false)
            .flag("boolean-flag")
            .targetingKey("user-123")
            .result(new Result<>(true).reason(TARGETING_MATCH.name()).variant("enabled")),
        new TestCase<>(0)
            .flag("integer-flag")
            .targetingKey("user-123")
            .result(new Result<>(42).reason(TARGETING_MATCH.name()).variant("forty-two")),
        new TestCase<>("default")
            .flag("rule-based-flag")
            .targetingKey("user-premium")
            .context("email", "john@company.com")
            .result(new Result<>("premium").reason(TARGETING_MATCH.name()).variant("premium")),
        new TestCase<>("default")
            .flag("rule-based-flag")
            .targetingKey("user-basic")
            .context("email", "john@gmail.com")
            .result(new Result<>("basic").reason(TARGETING_MATCH.name()).variant("basic")),
        new TestCase<>("default")
            .flag("numeric-rule-flag")
            .targetingKey("user-vip")
            .context("score", 850)
            .result(new Result<>("vip").reason(TARGETING_MATCH.name()).variant("vip")),
        new TestCase<>("default")
            .flag("null-check-flag")
            .targetingKey("user-no-beta")
            .result(new Result<>("no-beta").reason(TARGETING_MATCH.name()).variant("no-beta")),
        new TestCase<>("default")
            .flag("region-flag")
            .targetingKey("user-regional")
            .context("region", "us-east-1")
            .result(new Result<>("regional").reason(TARGETING_MATCH.name()).variant("regional")),
        new TestCase<>("default")
            .flag("time-based-flag")
            .targetingKey("user-regional")
            .context("region", "us-east-1")
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("shard-flag")
            .targetingKey("user-shard-test")
            .result(
                new Result<>("default")
                    // Result depends on shard calculation - either match or default
                    .reason(TARGETING_MATCH.name(), DEFAULT.name())),
        new TestCase<>(0)
            .flag("string-number-flag")
            .targetingKey("user-123")
            .result(new Result<>(123).reason(TARGETING_MATCH.name()).variant("string-num")),
        new TestCase<>("default")
            .flag("broken-flag")
            .targetingKey("user-123")
            .result(new Result<>("default").reason(ERROR.name()).errorCode(ErrorCode.GENERAL)),
        new TestCase<>("default")
            .flag("lt-flag")
            .targetingKey("user-123")
            .context("score", 750)
            .result(new Result<>("low-score").reason(TARGETING_MATCH.name()).variant("low")),
        new TestCase<>("default")
            .flag("lte-flag")
            .targetingKey("user-123")
            .context("score", 800)
            .result(new Result<>("medium-score").reason(TARGETING_MATCH.name()).variant("medium")),
        new TestCase<>("default")
            .flag("gt-flag")
            .targetingKey("user-123")
            .context("score", 950)
            .result(new Result<>("high-score").reason(TARGETING_MATCH.name()).variant("high")),
        new TestCase<>("default")
            .flag("not-matches-flag")
            .targetingKey("user-123")
            .context("email", "user@yahoo.com")
            .result(new Result<>("external").reason(TARGETING_MATCH.name()).variant("external")),
        new TestCase<>("default")
            .flag("not-one-of-flag")
            .targetingKey("user-123")
            .context("region", "ap-south-1")
            .result(new Result<>("other-region").reason(TARGETING_MATCH.name()).variant("other")),
        new TestCase<>("default")
            .flag("double-equals-flag")
            .targetingKey("user-123")
            .context("rate", 3.14159)
            .result(new Result<>("pi-value").reason(TARGETING_MATCH.name()).variant("pi")),
        new TestCase<>("default")
            .flag("nested-attr-flag")
            .targetingKey("user-123")
            .context("user.profile.level", "premium")
            .result(new Result<>("premium-user").reason(TARGETING_MATCH.name()).variant("premium")),
        new TestCase<>("default")
            .flag("lt-flag")
            .targetingKey("user-123")
            .context("score", "not-a-number")
            .result(
                new Result<>("default").reason(ERROR.name()).errorCode(ErrorCode.TYPE_MISMATCH)),
        new TestCase<>("default")
            .flag("exposure-flag")
            .targetingKey("user-123")
            .result(
                new Result<>("tracked-value")
                    .reason(TARGETING_MATCH.name())
                    .variant("tracked")
                    .flagMetadata("allocationKey", "exposure-alloc")
                    .flagMetadata("doLog", true)),
        new TestCase<>("default")
            .flag("exposure-logging-flag")
            .targetingKey("user-exposure")
            .context("feature", "premium")
            .result(
                new Result<>("logged-value")
                    .reason(TARGETING_MATCH.name())
                    .variant("logged")
                    .flagMetadata("allocationKey", "logged-alloc")
                    .flagMetadata("doLog", true)),
        new TestCase<>("default")
            .flag("double-comparison-flag")
            .targetingKey("user-123")
            .context("score", 3.14159)
            .result(new Result<>("exact-match").reason(TARGETING_MATCH.name()).variant("exact")),
        new TestCase<>("default")
            .flag("numeric-one-of-flag")
            .targetingKey("user-123")
            .context("score", 3.14159)
            .result(
                new Result<>("numeric-matched")
                    .reason(TARGETING_MATCH.name())
                    .variant("numeric-match")),
        new TestCase<>("default")
            .flag("numeric-not-one-of-flag")
            .targetingKey("user-123")
            .context("score", 42.0)
            .result(new Result<>("not-in-set").reason(TARGETING_MATCH.name()).variant("excluded")),
        new TestCase<>("default")
            .flag("is-null-false-flag")
            .targetingKey("user-123")
            .context("attr", "value")
            .result(new Result<>("not-null").reason(TARGETING_MATCH.name()).variant("not-null")),
        new TestCase<>("default")
            .flag("is-null-non-boolean-flag")
            .targetingKey("user-123")
            .result(
                new Result<>("null-match").reason(TARGETING_MATCH.name()).variant("null-match")),
        new TestCase<>("default")
            .flag("null-attribute-flag")
            .targetingKey("user-123")
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("not-matches-positive-flag")
            .targetingKey("user-123")
            .context("email", "user@gmail.com")
            .result(
                new Result<>("external-email").reason(TARGETING_MATCH.name()).variant("external")),
        new TestCase<>("default")
            .flag("not-one-of-positive-flag")
            .targetingKey("user-123")
            .context("region", "ap-south-1")
            .result(new Result<>("other-region").reason(TARGETING_MATCH.name()).variant("other")),
        new TestCase<>("default")
            .flag("false-numeric-comparisons-flag")
            .targetingKey("user-123")
            .context("score", 750)
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("empty-splits-flag")
            .targetingKey("user-123")
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("empty-conditions-flag")
            .targetingKey("user-123")
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("shard-matching-flag")
            .targetingKey("specific-key-that-matches-shard")
            .result(
                new Result<>("shard-matched").reason(TARGETING_MATCH.name()).variant("matched")),
        new TestCase<>("default")
            .flag("future-allocation-flag")
            .targetingKey("user-123")
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("id-attribute-flag")
            .targetingKey("user-special-id")
            .result(new Result<>("id-resolved").reason(TARGETING_MATCH.name()).variant("id-match")),
        new TestCase<>("default")
            .flag("non-iterable-condition-flag")
            .targetingKey("user-123")
            .context("attr", "test-value")
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("gt-false-flag")
            .targetingKey("user-123")
            .context("score", 500)
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("lte-false-flag")
            .targetingKey("user-123")
            .context("score", 600)
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("lt-false-flag")
            .targetingKey("user-123")
            .context("score", 700)
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("not-matches-false-flag")
            .targetingKey("user-123")
            .context("email", "user@company.com")
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("not-one-of-false-flag")
            .targetingKey("user-123")
            .context("region", "us-east-1")
            .result(new Result<>("default").reason(DEFAULT.name())),
        new TestCase<>("default")
            .flag("null-context-values-flag")
            .targetingKey("user-123")
            .context("nullAttr", (String) null)
            .result(
                new Result<>("null-handled")
                    .reason(TARGETING_MATCH.name())
                    .variant("null-variant")));
  }

  @MethodSource("evaluateTestCases")
  @ParameterizedTest
  public <E> void testEvaluate(final TestCase<E> testCase) {
    final DDEvaluator evaluator = new DDEvaluator(mock(Runnable.class));
    evaluator.accept(createTestConfiguration());
    final ProviderEvaluation<E> details =
        evaluator.evaluate(testCase.type, testCase.flag, testCase.defaultValue, testCase.context);
    final Result<E> expected = testCase.result;
    assertThat(details.getValue(), equalTo(expected.value));
    assertThat(details.getReason(), oneOf(expected.reason));
    assertThat(details.getVariant(), equalTo(expected.variant));
    assertThat(details.getErrorCode(), equalTo(expected.errorCode));
    assertThat(details.getErrorCode(), equalTo(expected.errorCode));
    final String expectedAllocation = (String) expected.flagMetadata.get("allocationKey");
    if (expectedAllocation != null) {
      assertThat(details.getFlagMetadata().getString("allocationKey"), equalTo(expectedAllocation));
    }
    if (shouldDispatchExposure(expected)) {
      verify(exposureListener, times(1)).accept(exposureEventCaptor.capture());
      final ExposureEvent capturedEvent = exposureEventCaptor.getValue();
      assertThat(capturedEvent.flag.key, equalTo(testCase.flag));
      assertThat(capturedEvent.allocation.key, equalTo(expectedAllocation));
      assertThat(capturedEvent.variant.key, equalTo(testCase.result.variant));
      assertThat(capturedEvent.subject.id, equalTo(testCase.context.getTargetingKey()));
      for (final Map.Entry<String, Object> entry : testCase.context.asObjectMap().entrySet()) {
        assertThat(capturedEvent.subject.attributes, hasEntry(entry.getKey(), entry.getValue()));
      }
    } else {
      verify(exposureListener, times(0)).accept(any(ExposureEvent.class));
    }
  }

  private static boolean shouldDispatchExposure(final Result<?> result) {
    final Boolean doLog = (Boolean) result.flagMetadata.get("doLog");
    return doLog != null && doLog;
  }

  private ServerConfiguration createTestConfiguration() {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "simple-string", createSimpleFlag("simple-string", ValueType.STRING, "test-value", "on"));
    flags.put("boolean-flag", createSimpleFlag("boolean-flag", ValueType.BOOLEAN, true, "enabled"));
    flags.put("integer-flag", createSimpleFlag("integer-flag", ValueType.INTEGER, 42, "forty-two"));
    flags.put("double-flag", createSimpleFlag("double-flag", ValueType.NUMERIC, 3.14, "pi"));
    flags.put(
        "string-number-flag",
        createSimpleFlag("string-number-flag", ValueType.STRING, "123", "string-num"));
    flags.put("disabled-flag", new Flag("disabled-flag", false, ValueType.BOOLEAN, null, null));
    flags.put("rule-based-flag", createRuleBasedFlag());
    flags.put("numeric-rule-flag", createNumericRuleFlag());
    flags.put("null-check-flag", createNullCheckFlag());
    flags.put("region-flag", createOneOfRuleFlag());
    flags.put("time-based-flag", createTimeBasedFlag());
    flags.put("shard-flag", createShardBasedFlag());
    flags.put("broken-flag", createBrokenFlag());
    flags.put("lt-flag", createLessThanFlag());
    flags.put("lte-flag", createLessThanOrEqualFlag());
    flags.put("gt-flag", createGreaterThanFlag());
    flags.put("not-matches-flag", createNotMatchesFlag());
    flags.put("not-one-of-flag", createNotOneOfFlag());
    flags.put("double-equals-flag", createDoubleEqualsFlag());
    flags.put("nested-attr-flag", createNestedAttributeFlag());
    flags.put("exposure-flag", createExposureFlag());
    flags.put("exposure-logging-flag", createExposureLoggingFlag());
    flags.put("double-comparison-flag", createDoubleComparisonFlag());
    flags.put("numeric-one-of-flag", createNumericOneOfFlag());
    flags.put("numeric-not-one-of-flag", createNumericNotOneOfFlag());
    flags.put("is-null-false-flag", createIsNullFalseFlag());
    flags.put("is-null-non-boolean-flag", createIsNullNonBooleanFlag());
    flags.put("null-attribute-flag", createNullAttributeFlag());
    flags.put("not-matches-positive-flag", createNotMatchesPositiveFlag());
    flags.put("not-one-of-positive-flag", createNotOneOfPositiveFlag());
    flags.put("false-numeric-comparisons-flag", createFalseNumericComparisonsFlag());
    flags.put("empty-splits-flag", createEmptySplitsFlag());
    flags.put("empty-conditions-flag", createEmptyConditionsFlag());
    flags.put("shard-matching-flag", createShardMatchingFlag());
    flags.put("future-allocation-flag", createFutureAllocationFlag());
    flags.put("id-attribute-flag", createIdAttributeFlag());
    flags.put("non-iterable-condition-flag", createNonIterableConditionFlag());
    flags.put("gt-false-flag", createGtFalseFlag());
    flags.put("lte-false-flag", createLteFalseFlag());
    flags.put("lt-false-flag", createLtFalseFlag());
    flags.put("not-matches-false-flag", createNotMatchesFalseFlag());
    flags.put("not-one-of-false-flag", createNotOneOfFalseFlag());
    flags.put("null-context-values-flag", createNullContextValuesFlag());
    return new ServerConfiguration(null, null, null, flags);
  }

  private Flag createSimpleFlag(String key, ValueType type, Object value, String variantKey) {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put(variantKey, new Variant(variantKey, value));
    final List<Split> splits = singletonList(new Split(emptyList(), variantKey, null));
    final List<Allocation> allocations =
        singletonList(new Allocation("alloc1", null, null, null, splits, false));
    return new Flag(key, true, type, variants, allocations);
  }

  private Flag createRuleBasedFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("premium", new Variant("premium", "premium"));
    variants.put("basic", new Variant("basic", "basic"));

    // Rule: email MATCHES @company.com$ -> premium
    final List<ConditionConfiguration> premiumConditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.MATCHES, "email", "@company\\.com$"));
    final List<Rule> premiumRules = singletonList(new Rule(premiumConditions));
    final List<Split> premiumSplits = singletonList(new Split(emptyList(), "premium", null));
    final Allocation premiumAllocation =
        new Allocation("premium-alloc", premiumRules, null, null, premiumSplits, false);

    // Fallback allocation for basic
    final List<Split> basicSplits = singletonList(new Split(emptyList(), "basic", null));
    final Allocation basicAllocation =
        new Allocation("basic-alloc", null, null, null, basicSplits, false);

    final List<Allocation> allocations = asList(premiumAllocation, basicAllocation);

    return new Flag("rule-based-flag", true, ValueType.STRING, variants, allocations);
  }

  private Flag createNumericRuleFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("vip", new Variant("vip", "vip"));
    variants.put("regular", new Variant("regular", "regular"));

    // Rule: score >= 800 -> vip
    final List<ConditionConfiguration> vipConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.GTE, "score", 800));
    final List<Rule> vipRules = singletonList(new Rule(vipConditions));
    final List<Split> vipSplits = singletonList(new Split(emptyList(), "vip", null));
    final Allocation vipAllocation =
        new Allocation("vip-alloc", vipRules, null, null, vipSplits, false);

    // Fallback
    final List<Split> regularSplits = singletonList(new Split(emptyList(), "regular", null));
    final Allocation regularAllocation =
        new Allocation("regular-alloc", null, null, null, regularSplits, false);

    return new Flag(
        "numeric-rule-flag",
        true,
        ValueType.STRING,
        variants,
        asList(vipAllocation, regularAllocation));
  }

  private Flag createNullCheckFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("no-beta", new Variant("no-beta", "no-beta"));
    variants.put("has-beta", new Variant("has-beta", "has-beta"));

    // Rule: beta_feature IS_NULL (true) -> no-beta
    final List<ConditionConfiguration> noBetaConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.IS_NULL, "beta_feature", true));
    final List<Rule> noBetaRules = singletonList(new Rule(noBetaConditions));
    final List<Split> noBetaSplits = singletonList(new Split(emptyList(), "no-beta", null));
    final Allocation noBetaAllocation =
        new Allocation("no-beta-alloc", noBetaRules, null, null, noBetaSplits, false);

    // Fallback
    final List<Split> hasBetaSplits = singletonList(new Split(emptyList(), "has-beta", null));
    final Allocation hasBetaAllocation =
        new Allocation("has-beta-alloc", null, null, null, hasBetaSplits, false);

    return new Flag(
        "null-check-flag",
        true,
        ValueType.STRING,
        variants,
        asList(noBetaAllocation, hasBetaAllocation));
  }

  private Flag createOneOfRuleFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("regional", new Variant("regional", "regional"));
    variants.put("global", new Variant("global", "global"));

    // Rule: region ONE_OF [us-east-1, us-west-2, eu-west-1] -> regional
    final List<String> allowedRegions = asList("us-east-1", "us-west-2", "eu-west-1");
    final List<ConditionConfiguration> regionalConditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.ONE_OF, "region", allowedRegions));
    final List<Rule> regionalRules = singletonList(new Rule(regionalConditions));
    final List<Split> regionalSplits = singletonList(new Split(emptyList(), "regional", null));
    final Allocation regionalAllocation =
        new Allocation("regional-alloc", regionalRules, null, null, regionalSplits, false);

    // Fallback
    final List<Split> globalSplits = singletonList(new Split(emptyList(), "global", null));
    final Allocation globalAllocation =
        new Allocation("global-alloc", null, null, null, globalSplits, false);

    return new Flag(
        "region-flag",
        true,
        ValueType.STRING,
        variants,
        asList(regionalAllocation, globalAllocation));
  }

  private Flag createTimeBasedFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("time-limited", new Variant("time-limited", "time-limited"));

    final List<Split> splits = singletonList(new Split(emptyList(), "time-limited", null));

    // Allocation that ended in 2022 (should be inactive)
    final List<Allocation> allocations =
        singletonList(
            new Allocation(
                "time-alloc",
                null,
                parseDate("2022-01-01T00:00:00Z"),
                parseDate("2022-12-31T23:59:59Z"),
                splits,
                false));

    return new Flag("time-based-flag", true, ValueType.STRING, variants, allocations);
  }

  private Flag createShardBasedFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("shard-variant", new Variant("shard-variant", "shard-value"));

    // Create a shard that includes some range
    final List<ShardRange> ranges = singletonList(new ShardRange(0, 50)); // 0-49 out of 100
    final List<Shard> shards = singletonList(new Shard("test-salt", ranges, 100));

    final List<Split> splits = singletonList(new Split(shards, "shard-variant", null));

    final List<Allocation> allocations =
        singletonList(new Allocation("shard-alloc", null, null, null, splits, false));

    return new Flag("shard-flag", true, ValueType.STRING, variants, allocations);
  }

  private Flag createBrokenFlag() {
    // Create a flag with missing variant
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("existing", new Variant("existing", "value"));

    final List<Split> splits = singletonList(new Split(emptyList(), "missing-variant", null));

    final List<Allocation> allocations =
        singletonList(new Allocation("alloc1", null, null, null, splits, false));

    return new Flag("broken-flag", true, ValueType.STRING, variants, allocations);
  }

  private Flag createComparisonFlag(
      String flagKey,
      String allocKey,
      String variantKey,
      String variantValue,
      ConditionOperator operator,
      String attribute,
      Object threshold) {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put(variantKey, new Variant(variantKey, variantValue));

    final List<ConditionConfiguration> conditions =
        singletonList(new ConditionConfiguration(operator, attribute, threshold));
    final List<Rule> rules = singletonList(new Rule(conditions));
    final List<Split> splits = singletonList(new Split(emptyList(), variantKey, null));
    final Allocation allocation = new Allocation(allocKey, rules, null, null, splits, false);

    return new Flag(flagKey, true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createLessThanFlag() {
    return createComparisonFlag(
        "lt-flag", "low-alloc", "low", "low-score", ConditionOperator.LT, "score", 800);
  }

  private Flag createLessThanOrEqualFlag() {
    return createComparisonFlag(
        "lte-flag", "medium-alloc", "medium", "medium-score", ConditionOperator.LTE, "score", 800);
  }

  private Flag createGreaterThanFlag() {
    return createComparisonFlag(
        "gt-flag", "high-alloc", "high", "high-score", ConditionOperator.GT, "score", 900);
  }

  private Flag createNotOperatorFlag(
      String flagKey,
      String allocKey,
      String variantKey,
      String variantValue,
      ConditionOperator operator,
      String attribute,
      Object value) {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put(variantKey, new Variant(variantKey, variantValue));

    final List<ConditionConfiguration> conditions =
        singletonList(new ConditionConfiguration(operator, attribute, value));
    final List<Rule> rules = singletonList(new Rule(conditions));
    final List<Split> splits = singletonList(new Split(emptyList(), variantKey, null));
    final Allocation allocation = new Allocation(allocKey, rules, null, null, splits, false);

    return new Flag(flagKey, true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createNotMatchesFlag() {
    return createNotOperatorFlag(
        "not-matches-flag",
        "external-alloc",
        "external",
        "external",
        ConditionOperator.NOT_MATCHES,
        "email",
        "@company\\.com$");
  }

  private Flag createNotOneOfFlag() {
    final List<String> disallowedRegions = asList("us-east-1", "us-west-2", "eu-west-1");
    return createNotOperatorFlag(
        "not-one-of-flag",
        "other-alloc",
        "other",
        "other-region",
        ConditionOperator.NOT_ONE_OF,
        "region",
        disallowedRegions);
  }

  private Flag createDoubleEqualsFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("pi", new Variant("pi", "pi-value"));

    // This will test the double comparison in valuesEqual - match exact double value
    final List<ConditionConfiguration> piConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.MATCHES, "rate", "3.14159"));
    final List<Rule> piRules = singletonList(new Rule(piConditions));
    final List<Split> piSplits = singletonList(new Split(emptyList(), "pi", null));
    final Allocation piAllocation =
        new Allocation("pi-alloc", piRules, null, null, piSplits, false);

    return new Flag(
        "double-equals-flag", true, ValueType.STRING, variants, singletonList(piAllocation));
  }

  private Flag createNestedAttributeFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("premium", new Variant("premium", "premium-user"));

    // Rule: user.profile.level MATCHES premium -> premium
    final List<ConditionConfiguration> premiumConditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.MATCHES, "user.profile.level", "premium"));
    final List<Rule> premiumRules = singletonList(new Rule(premiumConditions));
    final List<Split> premiumSplits = singletonList(new Split(emptyList(), "premium", null));
    final Allocation premiumAllocation =
        new Allocation("premium-nested-alloc", premiumRules, null, null, premiumSplits, false);

    return new Flag(
        "nested-attr-flag", true, ValueType.STRING, variants, singletonList(premiumAllocation));
  }

  private Flag createExposureFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("tracked", new Variant("tracked", "tracked-value"));

    final List<Split> splits = singletonList(new Split(emptyList(), "tracked", null));
    // Create allocation with doLog=true to trigger exposure logging
    final List<Allocation> allocations =
        singletonList(new Allocation("exposure-alloc", null, null, null, splits, true));

    return new Flag("exposure-flag", true, ValueType.STRING, variants, allocations);
  }

  private Flag createDoubleComparisonFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("exact", new Variant("exact", "exact-match"));

    // This flag uses numeric comparison that will trigger the double comparison lambda
    final List<ConditionConfiguration> exactConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.LTE, "score", 3.14159));
    final List<Rule> exactRules = singletonList(new Rule(exactConditions));
    final List<Split> exactSplits = singletonList(new Split(emptyList(), "exact", null));
    final Allocation exactAllocation =
        new Allocation("exact-alloc", exactRules, null, null, exactSplits, false);

    return new Flag(
        "double-comparison-flag", true, ValueType.STRING, variants, singletonList(exactAllocation));
  }

  private Flag createExposureLoggingFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("logged", new Variant("logged", "logged-value"));

    // Rule: feature MATCHES premium -> logged
    final List<ConditionConfiguration> loggedConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.MATCHES, "feature", "premium"));
    final List<Rule> loggedRules = singletonList(new Rule(loggedConditions));
    final List<Split> loggedSplits = singletonList(new Split(emptyList(), "logged", null));
    // Create allocation with doLog=true to trigger exposure logging and allocationKey method
    final Allocation loggedAllocation =
        new Allocation("logged-alloc", loggedRules, null, null, loggedSplits, true);

    return new Flag(
        "exposure-logging-flag", true, ValueType.STRING, variants, singletonList(loggedAllocation));
  }

  private Flag createNumericOneOfFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("numeric-match", new Variant("numeric-match", "numeric-matched"));

    // Rule: score ONE_OF [3.14159, 2.71828] -> numeric-match
    // This will trigger valuesEqual with numeric comparison via lambda$valuesEqual$4
    final List<Double> numericValues = asList(3.14159, 2.71828);
    final List<ConditionConfiguration> numericConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.ONE_OF, "score", numericValues));
    final List<Rule> numericRules = singletonList(new Rule(numericConditions));
    final List<Split> numericSplits = singletonList(new Split(emptyList(), "numeric-match", null));
    final Allocation numericAllocation =
        new Allocation("numeric-alloc", numericRules, null, null, numericSplits, false);

    return new Flag(
        "numeric-one-of-flag", true, ValueType.STRING, variants, singletonList(numericAllocation));
  }

  private Flag createNumericNotOneOfFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("excluded", new Variant("excluded", "not-in-set"));

    // Rule: score NOT_ONE_OF [1.0, 2.0, 3.0] -> excluded
    // This will trigger valuesEqual with numeric comparison via lambda$valuesEqual$4
    final List<Double> excludedValues = asList(1.0, 2.0, 3.0);
    final List<ConditionConfiguration> excludedConditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.NOT_ONE_OF, "score", excludedValues));
    final List<Rule> excludedRules = singletonList(new Rule(excludedConditions));
    final List<Split> excludedSplits = singletonList(new Split(emptyList(), "excluded", null));
    final Allocation excludedAllocation =
        new Allocation("excluded-alloc", excludedRules, null, null, excludedSplits, false);

    return new Flag(
        "numeric-not-one-of-flag",
        true,
        ValueType.STRING,
        variants,
        singletonList(excludedAllocation));
  }

  private Flag createIsNullFalseFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("not-null", new Variant("not-null", "not-null"));

    // Rule: attr IS_NULL false -> not-null (checks if attr is NOT null)
    final List<ConditionConfiguration> notNullConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.IS_NULL, "attr", false));
    final List<Rule> notNullRules = singletonList(new Rule(notNullConditions));
    final List<Split> notNullSplits = singletonList(new Split(emptyList(), "not-null", null));
    final Allocation notNullAllocation =
        new Allocation("not-null-alloc", notNullRules, null, null, notNullSplits, false);

    return new Flag(
        "is-null-false-flag", true, ValueType.STRING, variants, singletonList(notNullAllocation));
  }

  private Flag createIsNullNonBooleanFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("null-match", new Variant("null-match", "null-match"));

    // Rule: missing_attr IS_NULL "string" -> null-match (non-boolean expectedNull value)
    final List<ConditionConfiguration> nullConditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.IS_NULL, "missing_attr", "string"));
    final List<Rule> nullRules = singletonList(new Rule(nullConditions));
    final List<Split> nullSplits = singletonList(new Split(emptyList(), "null-match", null));
    final Allocation nullAllocation =
        new Allocation("null-alloc", nullRules, null, null, nullSplits, false);

    return new Flag(
        "is-null-non-boolean-flag",
        true,
        ValueType.STRING,
        variants,
        singletonList(nullAllocation));
  }

  private Flag createNullAttributeFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("fallback", new Variant("fallback", "fallback"));

    // Rule: null_attribute MATCHES "test" -> should not match due to null attribute
    final List<ConditionConfiguration> nullAttrConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.MATCHES, null, "test"));
    final List<Rule> nullAttrRules = singletonList(new Rule(nullAttrConditions));
    final List<Split> nullAttrSplits = singletonList(new Split(emptyList(), "fallback", null));
    final Allocation nullAttrAllocation =
        new Allocation("null-attr-alloc", nullAttrRules, null, null, nullAttrSplits, false);

    return new Flag(
        "null-attribute-flag", true, ValueType.STRING, variants, singletonList(nullAttrAllocation));
  }

  private Flag createNotMatchesPositiveFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("external", new Variant("external", "external-email"));

    // Rule: email NOT_MATCHES "@company.com" -> external (should match gmail.com)
    final List<ConditionConfiguration> externalConditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.NOT_MATCHES, "email", "@company\\.com"));
    final List<Rule> externalRules = singletonList(new Rule(externalConditions));
    final List<Split> externalSplits = singletonList(new Split(emptyList(), "external", null));
    final Allocation externalAllocation =
        new Allocation("external-alloc", externalRules, null, null, externalSplits, false);

    return new Flag(
        "not-matches-positive-flag",
        true,
        ValueType.STRING,
        variants,
        singletonList(externalAllocation));
  }

  private Flag createNotOneOfPositiveFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("other", new Variant("other", "other-region"));

    // Rule: region NOT_ONE_OF ["us-east-1", "us-west-2", "eu-west-1"] -> other
    final List<String> excludedRegions = asList("us-east-1", "us-west-2", "eu-west-1");
    final List<ConditionConfiguration> otherConditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.NOT_ONE_OF, "region", excludedRegions));
    final List<Rule> otherRules = singletonList(new Rule(otherConditions));
    final List<Split> otherSplits = singletonList(new Split(emptyList(), "other", null));
    final Allocation otherAllocation =
        new Allocation("other-alloc", otherRules, null, null, otherSplits, false);

    return new Flag(
        "not-one-of-positive-flag",
        true,
        ValueType.STRING,
        variants,
        singletonList(otherAllocation));
  }

  private Flag createFalseNumericComparisonsFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("high-score", new Variant("high-score", "high-score"));

    // Rule: score GTE 800 -> high-score (test will provide 750, should fail)
    final List<ConditionConfiguration> highScoreConditions =
        singletonList(new ConditionConfiguration(ConditionOperator.GTE, "score", 800));
    final List<Rule> highScoreRules = singletonList(new Rule(highScoreConditions));
    final List<Split> highScoreSplits = singletonList(new Split(emptyList(), "high-score", null));
    final Allocation highScoreAllocation =
        new Allocation("high-score-alloc", highScoreRules, null, null, highScoreSplits, false);

    return new Flag(
        "false-numeric-comparisons-flag",
        true,
        ValueType.STRING,
        variants,
        singletonList(highScoreAllocation));
  }

  private Flag createEmptySplitsFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("default", new Variant("default", "default"));

    // Allocation with null splits
    final Allocation allocation =
        new Allocation("empty-splits-alloc", null, null, null, null, false);

    return new Flag(
        "empty-splits-flag", true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createEmptyConditionsFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("default", new Variant("default", "default"));

    // Rule with empty conditions list - this will be skipped, causing allocation to not match
    final Rule emptyConditionsRule = new Rule(emptyList());
    final List<Split> splits = singletonList(new Split(emptyList(), "default", null));
    final Allocation allocation =
        new Allocation(
            "empty-conditions-alloc",
            singletonList(emptyConditionsRule),
            null,
            null,
            splits,
            false);

    return new Flag(
        "empty-conditions-flag", true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createShardMatchingFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("matched", new Variant("matched", "shard-matched"));

    // Create shard that will match the specific targeting key
    final List<ShardRange> ranges =
        singletonList(new ShardRange(0, 100)); // Full range to ensure match
    final List<Shard> shards = singletonList(new Shard("test-salt", ranges, 100));
    final List<Split> splits = singletonList(new Split(shards, "matched", null));
    final Allocation allocation =
        new Allocation("shard-matching-alloc", null, null, null, splits, false);

    return new Flag(
        "shard-matching-flag", true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createFutureAllocationFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("future", new Variant("future", "future-value"));

    final List<Split> splits = singletonList(new Split(emptyList(), "future", null));

    // Allocation that starts in the future (2050)
    final Allocation allocation =
        new Allocation(
            "future-alloc", null, parseDate("2050-01-01T00:00:00Z"), null, splits, false);

    return new Flag(
        "future-allocation-flag", true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createIdAttributeFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("id-match", new Variant("id-match", "id-resolved"));

    // Rule that checks for "id" attribute (will use targeting key if not provided)
    final List<ConditionConfiguration> conditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.MATCHES, "id", "user-special-id"));
    final List<Rule> rules = singletonList(new Rule(conditions));
    final List<Split> splits = singletonList(new Split(emptyList(), "id-match", null));
    final Allocation allocation = new Allocation("id-attr-alloc", rules, null, null, splits, false);

    return new Flag(
        "id-attribute-flag", true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createNonIterableConditionFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("no-match", new Variant("no-match", "no-match"));

    // Rule with ONE_OF condition but non-iterable value (String instead of List)
    final List<ConditionConfiguration> conditions =
        singletonList(new ConditionConfiguration(ConditionOperator.ONE_OF, "attr", "single-value"));
    final List<Rule> rules = singletonList(new Rule(conditions));
    final List<Split> splits = singletonList(new Split(emptyList(), "no-match", null));
    final Allocation allocation =
        new Allocation("non-iterable-alloc", rules, null, null, splits, false);

    return new Flag(
        "non-iterable-condition-flag", true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createGtFalseFlag() {
    return createComparisonFlag(
        "gt-false-flag",
        "gt-false-alloc",
        "high",
        "high-value",
        ConditionOperator.GT,
        "score",
        600);
  }

  private Flag createLteFalseFlag() {
    return createComparisonFlag(
        "lte-false-flag",
        "lte-false-alloc",
        "low",
        "low-value",
        ConditionOperator.LTE,
        "score",
        500);
  }

  private Flag createLtFalseFlag() {
    return createComparisonFlag(
        "lt-false-flag",
        "lt-false-alloc",
        "very-low",
        "very-low-value",
        ConditionOperator.LT,
        "score",
        600);
  }

  private Flag createNotMatchesFalseFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("internal", new Variant("internal", "internal-email"));

    // Rule: email NOT_MATCHES "@company.com" -> internal (test provides company.com, should fail)
    final List<ConditionConfiguration> conditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.NOT_MATCHES, "email", "@company\\.com"));
    final List<Rule> rules = singletonList(new Rule(conditions));
    final List<Split> splits = singletonList(new Split(emptyList(), "internal", null));
    final Allocation allocation =
        new Allocation("not-matches-false-alloc", rules, null, null, splits, false);

    return new Flag(
        "not-matches-false-flag", true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createNotOneOfFalseFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("excluded", new Variant("excluded", "excluded-region"));

    // Rule: region NOT_ONE_OF ["us-east-1", "us-west-2"] -> excluded (test provides us-east-1,
    // should fail)
    final List<String> excludedRegions = asList("us-east-1", "us-west-2");
    final List<ConditionConfiguration> conditions =
        singletonList(
            new ConditionConfiguration(ConditionOperator.NOT_ONE_OF, "region", excludedRegions));
    final List<Rule> rules = singletonList(new Rule(conditions));
    final List<Split> splits = singletonList(new Split(emptyList(), "excluded", null));
    final Allocation allocation =
        new Allocation("not-one-of-false-alloc", rules, null, null, splits, false);

    return new Flag(
        "not-one-of-false-flag", true, ValueType.STRING, variants, singletonList(allocation));
  }

  private Flag createNullContextValuesFlag() {
    final Map<String, Variant> variants = new HashMap<>();
    variants.put("null-variant", new Variant("null-variant", "null-handled"));

    // Rule that will handle null context values in flattening
    final List<ConditionConfiguration> conditions =
        singletonList(new ConditionConfiguration(ConditionOperator.IS_NULL, "nullAttr", true));
    final List<Rule> rules = singletonList(new Rule(conditions));
    final List<Split> splits = singletonList(new Split(emptyList(), "null-variant", null));
    final Allocation allocation =
        new Allocation("null-context-alloc", rules, null, null, splits, false);

    return new Flag(
        "null-context-values-flag", true, ValueType.STRING, variants, singletonList(allocation));
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

  private static Date parseDate(String dateString) {
    try {
      SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
      formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
      return formatter.parse(dateString);
    } catch (ParseException e) {
      throw new RuntimeException("Failed to parse date: " + dateString, e);
    }
  }
}
