package datadog.trace.api.openfeature.evaluator

import static dev.openfeature.sdk.ErrorCode.*
import static dev.openfeature.sdk.Reason.*
import static java.util.Collections.*

import datadog.trace.api.openfeature.config.ufc.v1.Allocation
import datadog.trace.api.openfeature.config.ufc.v1.ConditionConfiguration
import datadog.trace.api.openfeature.config.ufc.v1.ConditionOperator
import datadog.trace.api.openfeature.config.ufc.v1.Flag
import datadog.trace.api.openfeature.config.ufc.v1.Rule
import datadog.trace.api.openfeature.config.ufc.v1.ServerConfiguration
import datadog.trace.api.openfeature.config.ufc.v1.Shard
import datadog.trace.api.openfeature.config.ufc.v1.ShardRange
import datadog.trace.api.openfeature.config.ufc.v1.Split
import datadog.trace.api.openfeature.config.ufc.v1.ValueType
import datadog.trace.api.openfeature.config.ufc.v1.Variant
import datadog.trace.api.openfeature.exposure.ExposureListener
import datadog.trace.api.openfeature.exposure.dto.ExposureEvent

import datadog.trace.test.util.DDSpecification
import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.MutableContext
import dev.openfeature.sdk.Structure
import dev.openfeature.sdk.Value

class FeatureFlagEvaluatorTest extends DDSpecification {

  void 'test parse dates'() {
    when:
    final value = FeatureFlagEvaluatorImpl.parseDate(date)

    then:
    value == expected

    where:
    date                        | expected
    // Valid ISO 8601 formats
    '2023-01-01T00:00:00Z'      | new Date(1672531200000L) // 2023-01-01 00:00:00 UTC
    '2023-12-31T23:59:59Z'      | new Date(1704067199000L) // 2023-12-31 23:59:59 UTC
    '2024-02-29T12:00:00Z'      | new Date(1709208000000L) // Leap year date
    '2023-01-01T00:00:00.000Z'  | new Date(1672531200000L) // With milliseconds
    '2023-06-15T14:30:45.123Z'  | new Date(1686839445123L) // With milliseconds

    // Non supported formats should return null
    '2023-01-01T01:00:00+01:00' | null // UTC+1
    '2023-01-01T00:00:00-05:00' | null // UTC-5
    '2023-01-01'                | null // Date only
    'invalid-date'              | null
    ''                          | null
    'not-a-date'                | null
    '2023/01/01T00:00:00Z'      | null // Wrong separator
  }

  void 'test value mapping'() {
    when:
    def result = null
    def thrown = null
    try {
      result = FeatureFlagEvaluatorImpl.mapValue(target, value)
    } catch (Throwable e) {
      thrown = e
    }

    then:
    if (expected == IllegalArgumentException) {
      thrown instanceof IllegalArgumentException
    } else {
      result == expected
    }

    where:
    target  | value        | expected
    // String mappings
    String  | 'hello'      | 'hello'
    String  | 123          | '123'
    String  | true         | 'true'
    String  | 3.14         | '3.14'
    String  | null         | null

    // Boolean mappings
    Boolean | true         | true
    Boolean | false        | false
    Boolean | 'true'       | true
    Boolean | 'false'      | false
    Boolean | 'TRUE'       | true
    Boolean | 'FALSE'      | false
    Boolean | 1            | true
    Boolean | 0            | false
    Boolean | null         | null

    // Integer mappings
    Integer | 42           | 42
    Integer | '42'         | 42
    Integer | 3.14         | 3
    Integer | '3.14'       | 3
    Integer | null         | null

    // Double mappings
    Double  | 3.14         | 3.14
    Double  | '3.14'       | 3.14
    Double  | 42           | 42.0
    Double  | '42'         | 42.0
    Double  | null         | null

    // Value mappings (OpenFeature Value objects)
    Value   | 'hello'      | Value.objectToValue('hello')
    Value   | 42           | Value.objectToValue(42)
    Value   | 3.14D        | Value.objectToValue(3.14D)
    Value   | true         | Value.objectToValue(true)
    Value   | null         | null

    // Unsupported
    Date    | '21-12-2023' | IllegalArgumentException
  }

  void 'test evaluate without a config'() {
    given:
    final evaluator = new FeatureFlagEvaluatorImpl(Stub(ExposureListener))

    when:
    final details = evaluator.evaluate(Integer, 'test', 23, Stub(EvaluationContext))

    then:
    details.value == 23
    details.reason == ERROR.name()
    details.errorCode == PROVIDER_NOT_READY
  }

  void 'test evaluate without context'() {
    given:
    final evaluator = new FeatureFlagEvaluatorImpl(Stub(ExposureListener))
    evaluator.onConfiguration(Stub(ServerConfiguration))

    when:
    final details = evaluator.evaluate(Integer, 'test', 23, null)

    then:
    details.value == 23
    details.reason == ERROR.name()
    details.errorCode == INVALID_CONTEXT
  }

  void 'test evaluate with bad allocations'() {
    given:
    final flags = [:]
    flags['null-allocation'] = new Flag('target', true, null, null, null)
    flags['empty-allocation'] = new Flag('target', true, null, null, emptyList())
    final evaluator = new FeatureFlagEvaluatorImpl(Stub(ExposureListener))
    evaluator.onConfiguration(Stub(ServerConfiguration))
    final ctx = new MutableContext('target').setTargetingKey('allocation')

    when:
    def details = evaluator.evaluate(Integer, 'null-allocation', 23, ctx)

    then:
    details.value == 23
    details.reason == ERROR.name()
    details.errorCode == GENERAL

    when:
    details = evaluator.evaluate(Integer, 'empty-allocation', 23, ctx)

    then:
    details.value == 23
    details.reason == ERROR.name()
    details.errorCode == GENERAL
  }

  void 'test flatting context'() {
    given:
    final context =
      new MutableContext(Value.objectToValue(attributes).asStructure().asMap())

    when:
    final result = FeatureFlagEvaluatorImpl.flattenContext(context)

    then:
    result == expected

    where:
    attributes                                                                       | expected
    emptyMap()                                                                       | emptyMap()
    ['integer': 1, 'double': 23D, 'boolean': true, 'string': 'string', 'null': null] | ['integer': 1, 'double': 23D, 'boolean': true, 'string': 'string', 'null': null]
    ['list': [1, 2, [4]]]                                                            | ['list[0]': 1, 'list[1]': 2, 'list[2][0]': 4]
    ['map': ['key1': 1, 'key2': 2, 'key3': ['key4': 4]]]                             | ['map.key1': 1, 'map.key2': 2, 'map.key3.key4': 4]
  }

  private static List<TestCase<?>> evaluateTestCases() {
    return [
      new TestCase<>('default')
      .flag('simple-string')
      .result(new Result<>('default').reason(ERROR.name()).errorCode(TARGETING_KEY_MISSING)),
      new TestCase<>('default')
      .flag('non-existent-flag')
      .targetingKey('user-123')
      .result(new Result<>('default').reason(ERROR.name()).errorCode(FLAG_NOT_FOUND)),
      new TestCase<>('default')
      .flag('disabled-flag')
      .targetingKey('user-123')
      .result(new Result<>('default').reason(DISABLED.name())),
      new TestCase<>('default')
      .flag('simple-string')
      .targetingKey('user-123')
      .result(new Result<>('test-value').reason(TARGETING_MATCH.name()).variant('on')),
      new TestCase<>(false)
      .flag('boolean-flag')
      .targetingKey('user-123')
      .result(new Result<>(true).reason(TARGETING_MATCH.name()).variant('enabled')),
      new TestCase<>(0)
      .flag('integer-flag')
      .targetingKey('user-123')
      .result(new Result<>(42).reason(TARGETING_MATCH.name()).variant('forty-two')),
      new TestCase<>('default')
      .flag('rule-based-flag')
      .targetingKey('user-premium')
      .context('email', 'john@company.com')
      .result(new Result<>('premium').reason(TARGETING_MATCH.name()).variant('premium')),
      new TestCase<>('default')
      .flag('rule-based-flag')
      .targetingKey('user-basic')
      .context('email', 'john@gmail.com')
      .result(new Result<>('basic').reason(TARGETING_MATCH.name()).variant('basic')),
      new TestCase<>('default')
      .flag('numeric-rule-flag')
      .targetingKey('user-vip')
      .context('score', 850)
      .result(new Result<>('vip').reason(TARGETING_MATCH.name()).variant('vip')),
      new TestCase<>('default')
      .flag('null-check-flag')
      .targetingKey('user-no-beta')
      .result(new Result<>('no-beta').reason(TARGETING_MATCH.name()).variant('no-beta')),
      new TestCase<>('default')
      .flag('region-flag')
      .targetingKey('user-regional')
      .context('region', 'us-east-1')
      .result(new Result<>('regional').reason(TARGETING_MATCH.name()).variant('regional')),
      new TestCase<>('default')
      .flag('time-based-flag')
      .targetingKey('user-regional')
      .context('region', 'us-east-1')
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('shard-flag')
      .targetingKey('user-shard-test')
      .result(new Result<>('default')
      // Result depends on shard calculation - either match or default
      .reason(TARGETING_MATCH.name(), DEFAULT.name())),
      new TestCase<>(0)
      .flag('string-number-flag')
      .targetingKey('user-123')
      .result(new Result<>(123).reason(TARGETING_MATCH.name()).variant('string-num')),
      new TestCase<>('default')
      .flag('broken-flag')
      .targetingKey('user-123')
      .result(new Result<>('default').reason(ERROR.name()).errorCode(GENERAL)),
      new TestCase<>('default')
      .flag('lt-flag')
      .targetingKey('user-123')
      .context('score', 750)
      .result(new Result<>('low-score').reason(TARGETING_MATCH.name()).variant('low')),
      new TestCase<>('default')
      .flag('lte-flag')
      .targetingKey('user-123')
      .context('score', 800)
      .result(new Result<>('medium-score').reason(TARGETING_MATCH.name()).variant('medium')),
      new TestCase<>('default')
      .flag('gt-flag')
      .targetingKey('user-123')
      .context('score', 950)
      .result(new Result<>('high-score').reason(TARGETING_MATCH.name()).variant('high')),
      new TestCase<>('default')
      .flag('not-matches-flag')
      .targetingKey('user-123')
      .context('email', 'user@yahoo.com')
      .result(new Result<>('external').reason(TARGETING_MATCH.name()).variant('external')),
      new TestCase<>('default')
      .flag('not-one-of-flag')
      .targetingKey('user-123')
      .context('region', 'ap-south-1')
      .result(new Result<>('other-region').reason(TARGETING_MATCH.name()).variant('other')),
      new TestCase<>('default')
      .flag('double-equals-flag')
      .targetingKey('user-123')
      .context('rate', 3.14159)
      .result(new Result<>('pi-value').reason(TARGETING_MATCH.name()).variant('pi')),
      new TestCase<>('default')
      .flag('nested-attr-flag')
      .targetingKey('user-123')
      .context('user.profile.level', 'premium')
      .result(new Result<>('premium-user').reason(TARGETING_MATCH.name()).variant('premium')),
      new TestCase<>('default')
      .flag('lt-flag')
      .targetingKey('user-123')
      .context('score', 'not-a-number')
      .result(new Result<>('default').reason(ERROR.name()).errorCode(TYPE_MISMATCH)),
      new TestCase<>('default')
      .flag('exposure-flag')
      .targetingKey('user-123')
      .result(new Result<>('tracked-value')
      .reason(TARGETING_MATCH.name())
      .variant('tracked')
      .flagMetadata('allocationKey', 'exposure-alloc')
      .flagMetadata('doLog', true)),
      new TestCase<>('default')
      .flag('exposure-logging-flag')
      .targetingKey('user-exposure')
      .context('feature', 'premium')
      .result(new Result<>('logged-value')
      .reason(TARGETING_MATCH.name())
      .variant('logged')
      .flagMetadata('allocationKey', 'logged-alloc')
      .flagMetadata('doLog', true)),
      new TestCase<>('default')
      .flag('double-comparison-flag')
      .targetingKey('user-123')
      .context('score', 3.14159)
      .result(new Result<>('exact-match').reason(TARGETING_MATCH.name()).variant('exact')),
      new TestCase<>('default')
      .flag('numeric-one-of-flag')
      .targetingKey('user-123')
      .context('score', 3.14159)
      .result(new Result<>('numeric-matched')
      .reason(TARGETING_MATCH.name())
      .variant('numeric-match')),
      new TestCase<>('default')
      .flag('numeric-not-one-of-flag')
      .targetingKey('user-123')
      .context('score', 42.0)
      .result(new Result<>('not-in-set').reason(TARGETING_MATCH.name()).variant('excluded')),
      new TestCase<>('default')
      .flag('is-null-false-flag')
      .targetingKey('user-123')
      .context('attr', 'value')
      .result(new Result<>('not-null').reason(TARGETING_MATCH.name()).variant('not-null')),
      new TestCase<>('default')
      .flag('is-null-non-boolean-flag')
      .targetingKey('user-123')
      .result(new Result<>('null-match').reason(TARGETING_MATCH.name()).variant('null-match')),
      new TestCase<>('default')
      .flag('null-attribute-flag')
      .targetingKey('user-123')
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('not-matches-positive-flag')
      .targetingKey('user-123')
      .context('email', 'user@gmail.com')
      .result(new Result<>('external-email').reason(TARGETING_MATCH.name()).variant('external')),
      new TestCase<>('default')
      .flag('not-one-of-positive-flag')
      .targetingKey('user-123')
      .context('region', 'ap-south-1')
      .result(new Result<>('other-region').reason(TARGETING_MATCH.name()).variant('other')),
      new TestCase<>('default')
      .flag('false-numeric-comparisons-flag')
      .targetingKey('user-123')
      .context('score', 750)
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('empty-splits-flag')
      .targetingKey('user-123')
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('empty-conditions-flag')
      .targetingKey('user-123')
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('shard-matching-flag')
      .targetingKey('specific-key-that-matches-shard')
      .result(new Result<>('shard-matched').reason(TARGETING_MATCH.name()).variant('matched')),
      new TestCase<>('default')
      .flag('future-allocation-flag')
      .targetingKey('user-123')
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('id-attribute-flag')
      .targetingKey('user-special-id')
      .result(new Result<>('id-resolved').reason(TARGETING_MATCH.name()).variant('id-match')),
      new TestCase<>('default')
      .flag('non-iterable-condition-flag')
      .targetingKey('user-123')
      .context('attr', 'test-value')
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('gt-false-flag')
      .targetingKey('user-123')
      .context('score', 500)
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('lte-false-flag')
      .targetingKey('user-123')
      .context('score', 600)
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('lt-false-flag')
      .targetingKey('user-123')
      .context('score', 700)
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('not-matches-false-flag')
      .targetingKey('user-123')
      .context('email', 'user@company.com')
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('not-one-of-false-flag')
      .targetingKey('user-123')
      .context('region', 'us-east-1')
      .result(new Result<>('default').reason(DEFAULT.name())),
      new TestCase<>('default')
      .flag('null-context-values-flag')
      .targetingKey('user-123')
      .context('nullAttr')
      .result(new Result<>('null-handled')
      .reason(TARGETING_MATCH.name())
      .variant('null-variant'))
    ]
  }

  void 'test evaluate'() {
    given:
    final listener = Mock(ExposureListener)
    final evaluator = new FeatureFlagEvaluatorImpl(listener)
    evaluator.onConfiguration(createTestConfiguration())

    when:
    final details = evaluator.evaluate(testCase.type, testCase.flag, testCase.defaultValue, testCase.context)

    then:
    details.value == testCase.result.value
    testCase.result.reason.contains(details.reason)
    details.variant == testCase.result.variant
    details.errorCode == testCase.result.errorCode
    if (testCase.result.flagMetadata.allocationKey != null) {
      details.flagMetadata.getString('allocationKey') == testCase.result.flagMetadata.allocationKey
    }
    if (shouldDispatchExposure(testCase.result)) {
      1 * listener.onExposure { ExposureEvent event ->
        event.flag.key == testCase.flag &&
          event.allocation.key == testCase.result.flagMetadata.allocationKey &&
          event.variant.key == testCase.result.variant &&
          event.subject.id == testCase.context.getTargetingKey() &&
          event.subject.attributes == testCase.context.asObjectMap()
      }
    } else {
      0 * listener.onExposure(_ as ExposureEvent)
    }

    where:
    testCase << evaluateTestCases()
  }

  private static boolean shouldDispatchExposure(final Result<?> result) {
    final Boolean doLog = result.flagMetadata.doLog as Boolean
    doLog != null && doLog
  }

  private static ServerConfiguration createTestConfiguration() {
    final flags = [
      'simple-string'                 : createSimpleFlag('simple-string', ValueType.STRING, 'test-value', 'on'),
      'boolean-flag'                  : createSimpleFlag('boolean-flag', ValueType.BOOLEAN, true, 'enabled'),
      'integer-flag'                  : createSimpleFlag('integer-flag', ValueType.INTEGER, 42, 'forty-two'),
      'double-flag'                   : createSimpleFlag('double-flag', ValueType.NUMERIC, 3.14, 'pi'),
      'string-number-flag'            : createSimpleFlag('string-number-flag', ValueType.STRING, '123', 'string-num'),
      'disabled-flag'                 : new Flag('disabled-flag', false, ValueType.BOOLEAN, null, null),
      'rule-based-flag'               : createRuleBasedFlag(),
      'numeric-rule-flag'             : createNumericRuleFlag(),
      'null-check-flag'               : createNullCheckFlag(),
      'region-flag'                   : createOneOfRuleFlag(),
      'time-based-flag'               : createTimeBasedFlag(),
      'shard-flag'                    : createShardBasedFlag(),
      'broken-flag'                   : createBrokenFlag(),
      'lt-flag'                       : createLessThanFlag(),
      'lte-flag'                      : createLessThanOrEqualFlag(),
      'gt-flag'                       : createGreaterThanFlag(),
      'not-matches-flag'              : createNotMatchesFlag(),
      'not-one-of-flag'               : createNotOneOfFlag(),
      'double-equals-flag'            : createDoubleEqualsFlag(),
      'nested-attr-flag'              : createNestedAttributeFlag(),
      'exposure-flag'                 : createExposureFlag(),
      'exposure-logging-flag'         : createExposureLoggingFlag(),
      'double-comparison-flag'        : createDoubleComparisonFlag(),
      'numeric-one-of-flag'           : createNumericOneOfFlag(),
      'numeric-not-one-of-flag'       : createNumericNotOneOfFlag(),
      'is-null-false-flag'            : createIsNullFalseFlag(),
      'is-null-non-boolean-flag'      : createIsNullNonBooleanFlag(),
      'null-attribute-flag'           : createNullAttributeFlag(),
      'not-matches-positive-flag'     : createNotMatchesPositiveFlag(),
      'not-one-of-positive-flag'      : createNotOneOfPositiveFlag(),
      'false-numeric-comparisons-flag': createFalseNumericComparisonsFlag(),
      'empty-splits-flag'             : createEmptySplitsFlag(),
      'empty-conditions-flag'         : createEmptyConditionsFlag(),
      'shard-matching-flag'           : createShardMatchingFlag(),
      'future-allocation-flag'        : createFutureAllocationFlag(),
      'id-attribute-flag'             : createIdAttributeFlag(),
      'non-iterable-condition-flag'   : createNonIterableConditionFlag(),
      'gt-false-flag'                 : createGtFalseFlag(),
      'lte-false-flag'                : createLteFalseFlag(),
      'lt-false-flag'                 : createLtFalseFlag(),
      'not-matches-false-flag'        : createNotMatchesFalseFlag(),
      'not-one-of-false-flag'         : createNotOneOfFalseFlag(),
      'null-context-values-flag'      : createNullContextValuesFlag()
    ]
    new ServerConfiguration(null, null, null, flags)
  }

  private static Flag createSimpleFlag(String key, ValueType type, Object value, String variantKey) {
    final variants = [(variantKey): new Variant(variantKey, value)]
    final splits = [new Split(emptyList(), variantKey, null)]
    final allocations = [new Allocation('alloc1', null, null, null, splits, false)]
    new Flag(key, true, type, variants, allocations)
  }

  private static Flag createRuleBasedFlag() {
    final variants = [
      premium: new Variant('premium', 'premium'),
      basic  : new Variant('basic', 'basic')
    ]

    // Rule: email MATCHES @company.com$ -> premium
    final premiumConditions = [new ConditionConfiguration(ConditionOperator.MATCHES, 'email', '@company\\.com\$')]
    final premiumRules = [new Rule(premiumConditions)]
    final premiumSplits = [new Split([], 'premium', null)]
    final premiumAllocation = new Allocation('premium-alloc', premiumRules, null, null, premiumSplits, false)

    // Fallback allocation for basic
    final basicSplits = [new Split([], 'basic', null)]
    final basicAllocation = new Allocation('basic-alloc', null, null, null, basicSplits, false)

    final allocations = [premiumAllocation, basicAllocation]

    return new Flag('rule-based-flag', true, ValueType.STRING, variants, allocations)
  }

  private static Flag createNumericRuleFlag() {
    final variants = [
      vip    : new Variant('vip', 'vip'),
      regular: new Variant('regular', 'regular')
    ]

    // Rule: score >= 800 -> vip
    final vipConditions = [new ConditionConfiguration(ConditionOperator.GTE, 'score', 800)]
    final vipRules = [new Rule(vipConditions)]
    final vipSplits = [new Split([], 'vip', null)]
    final vipAllocation = new Allocation('vip-alloc', vipRules, null, null, vipSplits, false)

    // Fallback
    final regularSplits = [new Split([], 'regular', null)]
    final regularAllocation = new Allocation('regular-alloc', null, null, null, regularSplits, false)

    new Flag('numeric-rule-flag', true, ValueType.STRING, variants, [vipAllocation, regularAllocation])
  }

  private static Flag createNullCheckFlag() {
    final variants = [
      'no-beta' : new Variant('no-beta', 'no-beta'),
      'has-beta': new Variant('has-beta', 'has-beta')
    ]

    // Rule: beta_feature IS_NULL (true) -> no-beta
    final noBetaConditions = [new ConditionConfiguration(ConditionOperator.IS_NULL, 'beta_feature', true)]
    final noBetaRules = [new Rule(noBetaConditions)]
    final noBetaSplits = [new Split([], 'no-beta', null)]
    final noBetaAllocation = new Allocation('no-beta-alloc', noBetaRules, null, null, noBetaSplits, false)

    // Fallback
    final hasBetaSplits = [new Split([], 'has-beta', null)]
    final hasBetaAllocation = new Allocation('has-beta-alloc', null, null, null, hasBetaSplits, false)

    new Flag('null-check-flag', true, ValueType.STRING, variants, [noBetaAllocation, hasBetaAllocation])
  }

  private static Flag createOneOfRuleFlag() {
    final variants = [
      regional: new Variant('regional', 'regional'),
      global  : new Variant('global', 'global')
    ]

    // Rule: region ONE_OF [us-east-1, us-west-2, eu-west-1] -> regional
    final allowedRegions = ['us-east-1', 'us-west-2', 'eu-west-1']
    final regionalConditions = [new ConditionConfiguration(ConditionOperator.ONE_OF, 'region', allowedRegions)]
    final regionalRules = [new Rule(regionalConditions)]
    final regionalSplits = [new Split([], 'regional', null)]
    final regionalAllocation = new Allocation('regional-alloc', regionalRules, null, null, regionalSplits, false)

    // Fallback
    final globalSplits = [new Split([], 'global', null)]
    final globalAllocation = new Allocation('global-alloc', null, null, null, globalSplits, false)

    new Flag('region-flag', true, ValueType.STRING, variants, [regionalAllocation, globalAllocation])
  }

  private static Flag createTimeBasedFlag() {
    final variants = ['time-limited': new Variant('time-limited', 'time-limited')]
    final splits = [new Split([], 'time-limited', null)]

    // Allocation that ended in 2022 (should be inactive)
    final allocations = [new Allocation('time-alloc', null, '2022-01-01T00:00:00Z', '2022-12-31T23:59:59Z', splits, false)]

    new Flag('time-based-flag', true, ValueType.STRING, variants, allocations)
  }

  private static Flag createShardBasedFlag() {
    final variants = ['shard-variant': new Variant('shard-variant', 'shard-value')]

    // Create a shard that includes some range
    final ranges = [new ShardRange(0, 50)] // 0-49 out of 100
    final shards = [new Shard('test-salt', ranges, 100)]
    final splits = [new Split(shards, 'shard-variant', null)]
    final allocations = [new Allocation('shard-alloc', null, null, null, splits, false)]

    new Flag('shard-flag', true, ValueType.STRING, variants, allocations)
  }

  private static Flag createBrokenFlag() {
    // Create a flag with missing variant
    final variants = [existing: new Variant('existing', 'value')]
    final splits = [new Split([], 'missing-variant', null)]
    final allocations = [new Allocation('alloc1', null, null, null, splits, false)]

    new Flag('broken-flag', true, ValueType.STRING, variants, allocations)
  }

  private static Flag createComparisonFlag(String flagKey,
    String allocKey,
    String variantKey,
    String variantValue,
    ConditionOperator operator,
    String attribute,
    Object threshold) {
    final variants = [(variantKey): new Variant(variantKey, variantValue)]
    final conditions = [new ConditionConfiguration(operator, attribute, threshold)]
    final rules = [new Rule(conditions)]
    final splits = [new Split([], variantKey, null)]
    final allocation = new Allocation(allocKey, rules, null, null, splits, false)

    new Flag(flagKey, true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createLessThanFlag() {
    return createComparisonFlag('lt-flag', 'low-alloc', 'low', 'low-score', ConditionOperator.LT, 'score', 800)
  }

  private static Flag createLessThanOrEqualFlag() {
    return createComparisonFlag('lte-flag', 'medium-alloc', 'medium', 'medium-score', ConditionOperator.LTE, 'score', 800)
  }

  private static Flag createGreaterThanFlag() {
    return createComparisonFlag('gt-flag', 'high-alloc', 'high', 'high-score', ConditionOperator.GT, 'score', 900)
  }

  private static Flag createNotOperatorFlag(String flagKey,
    String allocKey,
    String variantKey,
    String variantValue,
    ConditionOperator operator,
    String attribute,
    Object value) {
    final variants = [(variantKey): new Variant(variantKey, variantValue)]
    final conditions = [new ConditionConfiguration(operator, attribute, value)]
    final rules = [new Rule(conditions)]
    final splits = [new Split([], variantKey, null)]
    final allocation = new Allocation(allocKey, rules, null, null, splits, false)

    new Flag(flagKey, true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createNotMatchesFlag() {
    return createNotOperatorFlag('not-matches-flag',
      'external-alloc',
      'external',
      'external',
      ConditionOperator.NOT_MATCHES,
      'email',
      '@company\\.com$')
  }

  private static Flag createNotOneOfFlag() {
    final disallowedRegions = ['us-east-1', 'us-west-2', 'eu-west-1']
    createNotOperatorFlag('not-one-of-flag',
      'other-alloc',
      'other',
      'other-region',
      ConditionOperator.NOT_ONE_OF,
      'region',
      disallowedRegions)
  }

  private static Flag createDoubleEqualsFlag() {
    final variants = [pi: new Variant('pi', 'pi-value')]

    // This will test the double comparison in valuesEqual - match exact double value
    final piConditions = [new ConditionConfiguration(ConditionOperator.MATCHES, 'rate', '3.14159')]
    final piRules = [new Rule(piConditions)]
    final piSplits = [new Split([], 'pi', null)]
    final piAllocation = new Allocation('pi-alloc', piRules, null, null, piSplits, false)

    new Flag('double-equals-flag', true, ValueType.STRING, variants, [piAllocation])
  }

  private static Flag createNestedAttributeFlag() {
    final variants = [premium: new Variant('premium', 'premium-user')]

    // Rule: user.profile.level MATCHES premium -> premium
    final premiumConditions = [new ConditionConfiguration(ConditionOperator.MATCHES, 'user.profile.level', 'premium')]
    final premiumRules = [new Rule(premiumConditions)]
    final premiumSplits = [new Split([], 'premium', null)]
    final premiumAllocation = new Allocation('premium-nested-alloc', premiumRules, null, null, premiumSplits, false)

    new Flag('nested-attr-flag', true, ValueType.STRING, variants, [premiumAllocation])
  }

  private static Flag createExposureFlag() {
    final variants = [tracked: new Variant('tracked', 'tracked-value')]
    final splits = [new Split([], 'tracked', null)]
    // Create allocation with doLog=true to trigger exposure logging
    final allocations = [new Allocation('exposure-alloc', null, null, null, splits, true)]

    new Flag('exposure-flag', true, ValueType.STRING, variants, allocations)
  }

  private static Flag createDoubleComparisonFlag() {
    final variants = [exact: new Variant('exact', 'exact-match')]

    // This flag uses numeric comparison that will trigger the double comparison lambda
    final exactConditions = [new ConditionConfiguration(ConditionOperator.LTE, 'score', 3.14159)]
    final exactRules = [new Rule(exactConditions)]
    final exactSplits = [new Split([], 'exact', null)]
    final exactAllocation = new Allocation('exact-alloc', exactRules, null, null, exactSplits, false)

    new Flag('double-comparison-flag', true, ValueType.STRING, variants, [exactAllocation])
  }

  private static Flag createExposureLoggingFlag() {
    final variants = [logged: new Variant('logged', 'logged-value')]

    // Rule: feature MATCHES premium -> logged
    final loggedConditions = [new ConditionConfiguration(ConditionOperator.MATCHES, 'feature', 'premium')]
    final loggedRules = [new Rule(loggedConditions)]
    final loggedSplits = [new Split([], 'logged', null)]
    // Create allocation with doLog=true to trigger exposure logging and allocationKey method
    final loggedAllocation = new Allocation('logged-alloc', loggedRules, null, null, loggedSplits, true)

    new Flag('exposure-logging-flag', true, ValueType.STRING, variants, [loggedAllocation])
  }

  private static Flag createNumericOneOfFlag() {
    final variants = ['numeric-match': new Variant('numeric-match', 'numeric-matched')]

    // Rule: score ONE_OF [3.14159, 2.71828] -> numeric-match
    // This will trigger valuesEqual with numeric comparison via lambda$valuesEqual$4
    final numericValues = [3.14159D, 2.71828D]
    final numericConditions = [new ConditionConfiguration(ConditionOperator.ONE_OF, 'score', numericValues)]
    final numericRules = [new Rule(numericConditions)]
    final numericSplits = [new Split([], 'numeric-match', null)]
    final numericAllocation = new Allocation('numeric-alloc', numericRules, null, null, numericSplits, false)

    new Flag('numeric-one-of-flag', true, ValueType.STRING, variants, [numericAllocation])
  }

  private static Flag createNumericNotOneOfFlag() {
    final variants = [excluded: new Variant('excluded', 'not-in-set')]

    // Rule: score NOT_ONE_OF [1.0, 2.0, 3.0] -> excluded
    // This will trigger valuesEqual with numeric comparison via lambda$valuesEqual$4
    final excludedValues = [1.0, 2.0, 3.0]
    final excludedConditions = [new ConditionConfiguration(ConditionOperator.NOT_ONE_OF, 'score', excludedValues)]
    final excludedRules = [new Rule(excludedConditions)]
    final excludedSplits = [new Split([], 'excluded', null)]
    final excludedAllocation = new Allocation('excluded-alloc', excludedRules, null, null, excludedSplits, false)

    new Flag('numeric-not-one-of-flag', true, ValueType.STRING, variants, [excludedAllocation])
  }

  private static Flag createIsNullFalseFlag() {
    final variants = ['not-null': new Variant('not-null', 'not-null')]

    // Rule: attr IS_NULL false -> not-null (checks if attr is NOT null)
    final notNullConditions = [new ConditionConfiguration(ConditionOperator.IS_NULL, 'attr', false)]
    final notNullRules = [new Rule(notNullConditions)]
    final notNullSplits = [new Split([], 'not-null', null)]
    final notNullAllocation = new Allocation('not-null-alloc', notNullRules, null, null, notNullSplits, false)

    new Flag('is-null-false-flag', true, ValueType.STRING, variants, [notNullAllocation])
  }

  private static Flag createIsNullNonBooleanFlag() {
    final variants = ['null-match': new Variant('null-match', 'null-match')]

    // Rule: missing_attr IS_NULL 'string' -> null-match (non-boolean expectedNull value)
    final nullConditions = [new ConditionConfiguration(ConditionOperator.IS_NULL, 'missing_attr', 'string')]
    final nullRules = [new Rule(nullConditions)]
    final nullSplits = [new Split([], 'null-match', null)]
    final nullAllocation = new Allocation('null-alloc', nullRules, null, null, nullSplits, false)

    new Flag('is-null-non-boolean-flag', true, ValueType.STRING, variants, [nullAllocation])
  }

  private static Flag createNullAttributeFlag() {
    final variants = [fallback: new Variant('fallback', 'fallback')]

    // Rule: null_attribute MATCHES 'test' -> should not match due to null attribute
    final nullAttrConditions = [new ConditionConfiguration(ConditionOperator.MATCHES, null, 'test')]
    final nullAttrRules = [new Rule(nullAttrConditions)]
    final nullAttrSplits = [new Split([], 'fallback', null)]
    final nullAttrAllocation = new Allocation('null-attr-alloc', nullAttrRules, null, null, nullAttrSplits, false)

    new Flag('null-attribute-flag', true, ValueType.STRING, variants, [nullAttrAllocation])
  }

  private static Flag createNotMatchesPositiveFlag() {
    final variants = [external: new Variant('external', 'external-email')]

    // Rule: email NOT_MATCHES '@company.com' -> external (should match gmail.com)
    final externalConditions = [new ConditionConfiguration(ConditionOperator.NOT_MATCHES, 'email', '@company\\.com')]
    final externalRules = [new Rule(externalConditions)]
    final externalSplits = [new Split([], 'external', null)]
    final externalAllocation = new Allocation('external-alloc', externalRules, null, null, externalSplits, false)

    new Flag('not-matches-positive-flag', true, ValueType.STRING, variants, [externalAllocation])
  }

  private static Flag createNotOneOfPositiveFlag() {
    final variants = [other: new Variant('other', 'other-region')]

    // Rule: region NOT_ONE_OF ['us-east-1', 'us-west-2', 'eu-west-1'] -> other
    final excludedRegions = ['us-east-1', 'us-west-2', 'eu-west-1']
    final otherConditions = [new ConditionConfiguration(ConditionOperator.NOT_ONE_OF, 'region', excludedRegions)]
    final otherRules = [new Rule(otherConditions)]
    final otherSplits = [new Split([], 'other', null)]
    final otherAllocation = new Allocation('other-alloc', otherRules, null, null, otherSplits, false)

    new Flag('not-one-of-positive-flag', true, ValueType.STRING, variants, [otherAllocation])
  }

  private static Flag createFalseNumericComparisonsFlag() {
    final variants = ['high-score': new Variant('high-score', 'high-score')]

    // Rule: score GTE 800 -> high-score (test will provide 750, should fail)
    final highScoreConditions = [new ConditionConfiguration(ConditionOperator.GTE, 'score', 800)]
    final highScoreRules = [new Rule(highScoreConditions)]
    final highScoreSplits = [new Split([], 'high-score', null)]
    final highScoreAllocation = new Allocation('high-score-alloc', highScoreRules, null, null, highScoreSplits, false)

    new Flag('false-numeric-comparisons-flag', true, ValueType.STRING, variants, [highScoreAllocation])
  }

  private static Flag createEmptySplitsFlag() {
    final variants = ['default': new Variant('default', 'default')]

    // Allocation with null splits
    final allocation = new Allocation('empty-splits-alloc', null, null, null, null, false)

    new Flag('empty-splits-flag', true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createEmptyConditionsFlag() {
    final variants = ['default': new Variant('default', 'default')]

    // Rule with empty conditions list - this will be skipped, causing allocation to not match
    final emptyConditionsRule = new Rule([])
    final splits = [new Split([], 'default', null)]
    final allocation = new Allocation('empty-conditions-alloc', [emptyConditionsRule], null, null, splits, false)

    new Flag('empty-conditions-flag', true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createShardMatchingFlag() {
    final variants = [matched: new Variant('matched', 'shard-matched')]

    // Create shard that will match the specific targeting key
    final ranges = [new ShardRange(0, 100)] // Full range to ensure match
    final shards = [new Shard('test-salt', ranges, 100)]
    final splits = [new Split(shards, 'matched', null)]
    final allocation = new Allocation('shard-matching-alloc', null, null, null, splits, false)

    new Flag('shard-matching-flag', true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createFutureAllocationFlag() {
    final variants = [future: new Variant('future', 'future-value')]
    final splits = [new Split([], 'future', null)]

    // Allocation that starts in the future (2050)
    final allocation = new Allocation('future-alloc', null, '2050-01-01T00:00:00Z', null, splits, false)

    new Flag('future-allocation-flag', true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createIdAttributeFlag() {
    final variants = ['id-match': new Variant('id-match', 'id-resolved')]

    // Rule that checks for 'id' attribute (will use targeting key if not provided)
    final conditions = [new ConditionConfiguration(ConditionOperator.MATCHES, 'id', 'user-special-id')]
    final rules = [new Rule(conditions)]
    final splits = [new Split([], 'id-match', null)]
    final allocation = new Allocation('id-attr-alloc', rules, null, null, splits, false)

    new Flag('id-attribute-flag', true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createNonIterableConditionFlag() {
    final variants = ['no-match': new Variant('no-match', 'no-match')]

    // Rule with ONE_OF condition but non-iterable value (String instead of List)
    final conditions = [new ConditionConfiguration(ConditionOperator.ONE_OF, 'attr', 'single-value')]
    final rules = [new Rule(conditions)]
    final splits = [new Split([], 'no-match', null)]
    final allocation = new Allocation('non-iterable-alloc', rules, null, null, splits, false)

    new Flag('non-iterable-condition-flag', true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createGtFalseFlag() {
    createComparisonFlag('gt-false-flag',
      'gt-false-alloc',
      'high',
      'high-value',
      ConditionOperator.GT,
      'score',
      600)
  }

  private static Flag createLteFalseFlag() {
    createComparisonFlag('lte-false-flag',
      'lte-false-alloc',
      'low',
      'low-value',
      ConditionOperator.LTE,
      'score',
      500)
  }

  private static Flag createLtFalseFlag() {
    createComparisonFlag('lt-false-flag',
      'lt-false-alloc',
      'very-low',
      'very-low-value',
      ConditionOperator.LT,
      'score',
      600)
  }

  private static Flag createNotMatchesFalseFlag() {
    final variants = [internal: new Variant('internal', 'internal-email')]

    // Rule: email NOT_MATCHES '@company.com' -> internal (test provides company.com, should fail)
    final conditions = [new ConditionConfiguration(ConditionOperator.NOT_MATCHES, 'email', '@company\\.com')]
    final rules = [new Rule(conditions)]
    final splits = [new Split([], 'internal', null)]
    final allocation = new Allocation('not-matches-false-alloc', rules, null, null, splits, false)

    new Flag('not-matches-false-flag', true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createNotOneOfFalseFlag() {
    final variants = [excluded: new Variant('excluded', 'excluded-region')]

    // Rule: region NOT_ONE_OF ['us-east-1', 'us-west-2'] -> excluded (test provides us-east-1,
    // should fail)
    final excludedRegions = ['us-east-1', 'us-west-2']
    final conditions = [new ConditionConfiguration(ConditionOperator.NOT_ONE_OF, 'region', excludedRegions)]
    final rules = [new Rule(conditions)]
    final splits = [new Split([], 'excluded', null)]
    final allocation = new Allocation('not-one-of-false-alloc', rules, null, null, splits, false)

    new Flag('not-one-of-false-flag', true, ValueType.STRING, variants, [allocation])
  }

  private static Flag createNullContextValuesFlag() {
    final variants = ['null-variant': new Variant('null-variant', 'null-handled')]

    // Rule that will handle null context values in flattening
    final conditions = [new ConditionConfiguration(ConditionOperator.IS_NULL, 'nullAttr', true)]
    final rules = [new Rule(conditions)]
    final splits = [new Split([], 'null-variant', null)]
    final allocation = new Allocation('null-context-alloc', rules, null, null, splits, false)

    new Flag('null-context-values-flag', true, ValueType.STRING, variants, [allocation])
  }

  static class TestCase<E> {

    Class<E> type
    String flag
    E defaultValue
    final context = new MutableContext()
    Result<E> result

    @SuppressWarnings('unchecked')
    TestCase(final E defaultValue) {
      this.type = (Class<E>) defaultValue.getClass()
      this.defaultValue = defaultValue
    }

    TestCase<E> flag(String flag) {
      this.flag = flag
      return this
    }

    TestCase<E> targetingKey(final String targetingKey) {
      context.setTargetingKey(targetingKey)
      this
    }

    TestCase<E> context(final String key, final String value) {
      context.add(key, value)
      this
    }

    TestCase<E> context(final String key, final Integer value) {
      context.add(key, value)
      this
    }

    TestCase<E> context(final String key, final Double value) {
      context.add(key, value)
      this
    }

    TestCase<E> context(final String key, final Boolean value) {
      context.add(key, value)
      this
    }

    TestCase<E> context(final String key, final Structure value) {
      context.add(key, value)
      this
    }

    TestCase<E> context(final String key, final List<Value> value) {
      context.add(key, value)
      this
    }

    TestCase<E> context(final String key) {
      context.add(key, (String) null)
      this
    }

    TestCase<E> result(final Result<E> result) {
      this.result = result
      this
    }

    @Override
    String toString() {
      "TestCase{flag=$flag, defaultValue=$defaultValue, targetingKey=${context.getTargetingKey()}}"
    }
  }

  static class Result<E> {
    E value
    String variant
    String[] reason
    ErrorCode errorCode
    final Map<String, Object> flagMetadata = [:]

    Result(final E value) {
      this.value = value
    }

    Result<E> variant(final String variant) {
      this.variant = variant
      this
    }

    Result<E> errorCode(final ErrorCode errorCode) {
      this.errorCode = errorCode
      this
    }

    Result<E> reason(final String... reason) {
      this.reason = reason
      this
    }

    Result<E> flagMetadata(final String name, final Object value) {
      flagMetadata[name] = value
      this
    }
  }
}
