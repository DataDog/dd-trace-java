package datadog.trace.api.telemetry

import static datadog.trace.api.aiguard.AIGuard.Action.ABORT
import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW
import static datadog.trace.api.aiguard.AIGuard.Action.DENY
import static datadog.trace.api.telemetry.WafMetricCollector.AIGuardTruncationType.CONTENT
import static datadog.trace.api.telemetry.WafMetricCollector.AIGuardTruncationType.MESSAGES

import datadog.trace.test.util.DDSpecification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS
import static datadog.trace.api.telemetry.LoginVersion.V1
import static datadog.trace.api.telemetry.LoginVersion.V2

class WafMetricCollectorTest extends DDSpecification {

  public static final int DD_WAF_RUN_INTERNAL_ERROR = WafMetricCollector.WafErrorCode.INTERNAL_ERROR.getCode()
  public static final int DD_WAF_RUN_INVALID_OBJECT_ERROR = WafMetricCollector.WafErrorCode.INVALID_OBJECT.getCode()

  def "no metrics - drain empty list"() {
    when:
    WafMetricCollector.get().prepareMetrics()

    then:
    WafMetricCollector.get().drain().isEmpty()
  }

  def "put-get waf/rasp metrics"() {
    when:
    WafMetricCollector.get().wafInit('waf_ver1', 'rules.1', true)
    WafMetricCollector.get().wafUpdates('rules.2', true)
    WafMetricCollector.get().wafUpdates('rules.3', false)
    WafMetricCollector.get().raspRuleEval(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspRuleEval(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspRuleMatch(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspRuleEval(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspTimeout(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspErrorCode(RuleType.SHELL_INJECTION, DD_WAF_RUN_INTERNAL_ERROR)
    WafMetricCollector.get().wafErrorCode(DD_WAF_RUN_INTERNAL_ERROR)
    WafMetricCollector.get().raspErrorCode(RuleType.SQL_INJECTION, DD_WAF_RUN_INVALID_OBJECT_ERROR)
    WafMetricCollector.get().wafErrorCode(DD_WAF_RUN_INVALID_OBJECT_ERROR)
    WafMetricCollector.get().raspRuleSkipped(RuleType.SQL_INJECTION)

    WafMetricCollector.get().prepareMetrics()

    then:
    def metrics = WafMetricCollector.get().drain()

    def initMetric = (WafMetricCollector.WafInitRawMetric) metrics[0]
    initMetric.type == 'count'
    initMetric.value == 1
    initMetric.namespace == 'appsec'
    initMetric.metricName == 'waf.init'
    initMetric.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.1', 'success:true'].toSet()

    def updateMetric1 = (WafMetricCollector.WafUpdatesRawMetric) metrics[1]
    updateMetric1.type == 'count'
    updateMetric1.value == 1
    updateMetric1.namespace == 'appsec'
    updateMetric1.metricName == 'waf.updates'
    updateMetric1.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.2', 'success:true'].toSet()

    def updateMetric2 = (WafMetricCollector.WafUpdatesRawMetric) metrics[2]
    updateMetric2.type == 'count'
    updateMetric2.value == 2
    updateMetric2.namespace == 'appsec'
    updateMetric2.metricName == 'waf.updates'
    updateMetric2.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.3', 'success:false'].toSet()


    def raspRuleEvalSqli = (WafMetricCollector.RaspRuleEval) metrics[3]
    raspRuleEvalSqli.type == 'count'
    raspRuleEvalSqli.value == 3
    raspRuleEvalSqli.namespace == 'appsec'
    raspRuleEvalSqli.metricName == 'rasp.rule.eval'
    raspRuleEvalSqli.tags.toSet() == ['rule_type:sql_injection', 'waf_version:waf_ver1'].toSet()

    def raspRuleMatch = (WafMetricCollector.RaspRuleMatch) metrics[4]
    raspRuleMatch.type == 'count'
    raspRuleMatch.value == 1
    raspRuleMatch.namespace == 'appsec'
    raspRuleMatch.metricName == 'rasp.rule.match'
    raspRuleMatch.tags.toSet() == ['rule_type:sql_injection', 'waf_version:waf_ver1'].toSet()

    def raspTimeout = (WafMetricCollector.RaspTimeout) metrics[5]
    raspTimeout.type == 'count'
    raspTimeout.value == 1
    raspTimeout.namespace == 'appsec'
    raspTimeout.metricName == 'rasp.timeout'
    raspTimeout.tags.toSet() == ['rule_type:sql_injection', 'waf_version:waf_ver1'].toSet()

    def raspInvalidObjectCode = (WafMetricCollector.RaspError)metrics[6]
    raspInvalidObjectCode.type == 'count'
    raspInvalidObjectCode.value == 1
    raspInvalidObjectCode.namespace == 'appsec'
    raspInvalidObjectCode.metricName == 'rasp.error'
    raspInvalidObjectCode.tags.toSet() == [
      'rule_type:sql_injection',
      'waf_version:waf_ver1',
      'waf_error:' + DD_WAF_RUN_INVALID_OBJECT_ERROR
    ]
    .toSet()

    def raspInvalidCode = (WafMetricCollector.RaspError)metrics[7]
    raspInvalidCode.type == 'count'
    raspInvalidCode.value == 1
    raspInvalidCode.namespace == 'appsec'
    raspInvalidCode.metricName == 'rasp.error'
    raspInvalidCode.tags.toSet() == [
      'waf_version:waf_ver1',
      'rule_type:command_injection',
      'rule_variant:shell',
      'event_rules_version:rules.3',
      'waf_error:' + DD_WAF_RUN_INTERNAL_ERROR
    ].toSet()

    def wafInvalidCode = (WafMetricCollector.WafError)metrics[8]
    wafInvalidCode.type == 'count'
    wafInvalidCode.value == 1
    wafInvalidCode.namespace == 'appsec'
    wafInvalidCode.metricName == 'waf.error'
    wafInvalidCode.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'waf_error:' +DD_WAF_RUN_INVALID_OBJECT_ERROR
    ].toSet()

    def wafInvalidObjectCode = (WafMetricCollector.WafError)metrics[9]
    wafInvalidObjectCode.type == 'count'
    wafInvalidObjectCode.value == 1
    wafInvalidObjectCode.namespace == 'appsec'
    wafInvalidObjectCode.metricName == 'waf.error'
    wafInvalidObjectCode.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'waf_error:'+DD_WAF_RUN_INTERNAL_ERROR
    ].toSet()

    def raspRuleSkipped = (WafMetricCollector.AfterRequestRaspRuleSkipped) metrics[10]
    raspRuleSkipped.type == 'count'
    raspRuleSkipped.value == 1
    raspRuleSkipped.namespace == 'appsec'
    raspRuleSkipped.metricName == 'rasp.rule.skipped'
    raspRuleSkipped.tags.toSet() == ['rule_type:sql_injection', 'reason:after-request',].toSet()
  }

  def "overflowing WafMetricCollector does not crash"() {
    given:
    final limit = 1024
    def collector = WafMetricCollector.get()

    when:
    (0..limit * 2).each {
      collector.wafInit("foo", "bar", true)
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit * 2).each {
      collector.wafUpdates("bar", true)
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit * 2).each {
      collector.raspRuleEval(RuleType.SQL_INJECTION)
      collector.prepareMetrics()
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit * 2).each {
      collector.raspRuleEval(RuleType.SQL_INJECTION)
      collector.prepareMetrics()
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit * 2).each {
      collector.raspRuleEval(RuleType.SQL_INJECTION)
      collector.prepareMetrics()
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit
  }

  void 'test missing user login event metric'() {
    given:
    def collector = WafMetricCollector.get()
    final loginSuccessCount = 6
    final loginFailureCount = 3
    final signupCount = 2
    final latch = new CountDownLatch(1)
    final executors = Executors.newFixedThreadPool(4)
    final action = { LoginFramework framework, LoginEvent event ->
      latch.await()
      collector.missingUserLogin(framework, event)
    }

    when:
    (1..loginSuccessCount).each {
      executors.submit {
        action.call(LoginFramework.SPRING_SECURITY, LOGIN_SUCCESS)
      }
    }
    (1..loginFailureCount).each {
      executors.submit {
        action.call(LoginFramework.SPRING_SECURITY, LoginEvent.LOGIN_FAILURE)
      }
    }
    (1..signupCount).each {
      executors.submit {
        action.call(LoginFramework.SPRING_SECURITY, LoginEvent.SIGN_UP)
      }
    }

    latch.countDown()
    executors.shutdown()
    final finished = executors.awaitTermination(5, TimeUnit.SECONDS)

    then:
    finished
    collector.prepareMetrics()
    final drained = collector.drain()
    final metrics = drained.findAll {
      it.metricName == 'instrum.user_auth.missing_user_login'
    }
    metrics.size() == 3
    metrics.forEach { metric ->
      assert metric.namespace == 'appsec'
      assert metric.type == 'count'
      final tags = metric.tags.collectEntries {
        final parts = it.split(":")
        return [(parts[0]): parts[1]]
      }
      assert tags["framework"] == LoginFramework.SPRING_SECURITY.getTag()
      switch (tags["event_type"]) {
        case LOGIN_SUCCESS.getTag():
          assert metric.value == loginSuccessCount
          break
        case LoginEvent.LOGIN_FAILURE.getTag():
          assert metric.value == loginFailureCount
          break
        case LoginEvent.SIGN_UP.getTag():
          assert metric.value == signupCount
          break
        default:
          throw new IllegalArgumentException("Invalid event_type " + tags["event_type"])
      }
    }
  }

  void 'test missing user id event metric'() {
    given:
    def collector = WafMetricCollector.get()
    final count = 6
    final latch = new CountDownLatch(1)
    final executors = Executors.newFixedThreadPool(4)
    final action = { LoginFramework framework ->
      latch.await()
      collector.missingUserId(framework)
    }

    when:
    (1..count).each {
      executors.submit {
        action.call(LoginFramework.SPRING_SECURITY)
      }
    }

    latch.countDown()
    executors.shutdown()
    final finished = executors.awaitTermination(5, TimeUnit.SECONDS)

    then:
    finished
    collector.prepareMetrics()
    final drained = collector.drain()
    final metrics = drained.findAll {
      it.metricName == 'instrum.user_auth.missing_user_id'
    }
    metrics.size() == 1
    metrics.forEach { metric ->
      assert metric.namespace == 'appsec'
      assert metric.type == 'count'
      assert metric.value == count
      final tags = metric.tags.collectEntries {
        final parts = it.split(":")
        return [(parts[0]): parts[1]]
      }
      assert tags["framework"] == LoginFramework.SPRING_SECURITY.getTag()
      assert tags["event_type"] == "authenticated_request"
    }
  }

  def "test Rasp #ruleType metrics"() {
    when:
    WafMetricCollector.get().wafInit('waf_ver1', 'rules.1', true)
    WafMetricCollector.get().raspRuleEval(ruleType)
    WafMetricCollector.get().raspRuleEval(ruleType)
    WafMetricCollector.get().raspRuleMatch(ruleType)
    WafMetricCollector.get().raspRuleEval(ruleType)
    WafMetricCollector.get().raspTimeout(ruleType)
    WafMetricCollector.get().raspErrorCode(ruleType, DD_WAF_RUN_INTERNAL_ERROR)
    WafMetricCollector.get().wafErrorCode(DD_WAF_RUN_INTERNAL_ERROR)
    WafMetricCollector.get().raspRuleSkipped(ruleType)
    WafMetricCollector.get().prepareMetrics()

    then:
    def metrics = WafMetricCollector.get().drain()

    def raspRuleEval = (WafMetricCollector.RaspRuleEval) metrics[1]
    raspRuleEval.type == 'count'
    raspRuleEval.value == 3
    raspRuleEval.namespace == 'appsec'
    raspRuleEval.metricName == 'rasp.rule.eval'
    raspRuleEval.tags.toSet() == [
      'rule_type:command_injection',
      'rule_variant:' + ruleType.variant,
      'waf_version:waf_ver1',
      'event_rules_version:rules.1'
    ].toSet()

    def raspRuleMatch = (WafMetricCollector.RaspRuleMatch) metrics[2]
    raspRuleMatch.type == 'count'
    raspRuleMatch.value == 1
    raspRuleMatch.namespace == 'appsec'
    raspRuleMatch.metricName == 'rasp.rule.match'
    raspRuleMatch.tags.toSet() == [
      'rule_type:command_injection',
      'rule_variant:' + ruleType.variant,
      'waf_version:waf_ver1',
      'event_rules_version:rules.1'
    ].toSet()

    def raspTimeout = (WafMetricCollector.RaspTimeout) metrics[3]
    raspTimeout.type == 'count'
    raspTimeout.value == 1
    raspTimeout.namespace == 'appsec'
    raspTimeout.metricName == 'rasp.timeout'
    raspTimeout.tags.toSet() == [
      'rule_type:command_injection',
      'rule_variant:' + ruleType.variant,
      'waf_version:waf_ver1',
      'event_rules_version:rules.1'
    ].toSet()

    def raspInvalidCode = (WafMetricCollector.RaspError) metrics[4]
    raspInvalidCode.type == 'count'
    raspInvalidCode.value == 1
    raspInvalidCode.namespace == 'appsec'
    raspInvalidCode.metricName == 'rasp.error'
    raspInvalidCode.tags.toSet() == [
      'waf_version:waf_ver1',
      'rule_type:command_injection',
      'rule_variant:' + ruleType.variant,
      'event_rules_version:rules.1',
      'waf_error:' + DD_WAF_RUN_INTERNAL_ERROR
    ].toSet()

    def wafInvalidCode = (WafMetricCollector.WafError) metrics[5]
    wafInvalidCode.type == 'count'
    wafInvalidCode.value == 1
    wafInvalidCode.namespace == 'appsec'
    wafInvalidCode.metricName == 'waf.error'
    wafInvalidCode.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.1',
      'waf_error:' + DD_WAF_RUN_INTERNAL_ERROR
    ].toSet()

    def raspRuleSkipped = (WafMetricCollector.AfterRequestRaspRuleSkipped) metrics[6]
    raspRuleSkipped.type == 'count'
    raspRuleSkipped.value == 1
    raspRuleSkipped.namespace == 'appsec'
    raspRuleSkipped.metricName == 'rasp.rule.skipped'
    raspRuleSkipped.tags.toSet() == [
      'rule_type:command_injection',
      'rule_variant:' + ruleType.variant,
      'reason:after-request',
    ].toSet()

    where:
    ruleType << [RuleType.COMMAND_INJECTION, RuleType.SHELL_INJECTION]
  }

  void 'test login event metrics'() {
    when:
    WafMetricCollector.get().appSecSdkEvent(LOGIN_SUCCESS, V1)
    WafMetricCollector.get().appSecSdkEvent(LOGIN_FAILURE, V2)

    then:
    WafMetricCollector.get().prepareMetrics()
    final metrics = WafMetricCollector.get().drain()
    final sdkEvents = metrics.findAll { it.metricName == 'sdk.event' }

    final loginSuccess = sdkEvents[0]
    loginSuccess.type == 'count'
    loginSuccess.value == 1
    loginSuccess.namespace == 'appsec'
    loginSuccess.metricName == 'sdk.event'
    loginSuccess.tags == ['event_type:login_success', 'sdk_version:v1']

    final loginFailure = sdkEvents[1]
    loginFailure.type == 'count'
    loginFailure.value == 1
    loginFailure.namespace == 'appsec'
    loginFailure.metricName == 'sdk.event'
    loginFailure.tags == ['event_type:login_failure', 'sdk_version:v2']
  }

  void 'test waf request metrics'() {
    given:
    def collector = WafMetricCollector.get()

    when:
    collector.wafRequest(
      triggered,
      blocked,
      wafError,
      wafTimeout,
      blockFailure,
      rateLimited,
      inputTruncated
      )

    then:
    collector.prepareMetrics()
    def metrics = collector.drain()
    def requestMetrics = metrics.findAll { it.metricName == 'waf.requests' }

    final metric = requestMetrics[0]
    metric.type == 'count'
    metric.metricName == 'waf.requests'
    metric.namespace == 'appsec'
    metric.tags == [
      "waf_version:waf_ver1",
      "event_rules_version:rules.1",
      "rule_triggered:${triggered}",
      "request_blocked:${blocked}",
      "waf_error:${wafError}",
      "waf_timeout:${wafTimeout}",
      "block_failure:${blockFailure}",
      "rate_limited:${rateLimited}",
      "input_truncated:${inputTruncated}"
    ]

    where:
    [triggered, blocked, wafError, wafTimeout, blockFailure, rateLimited, inputTruncated] << allBooleanCombinations(7)
  }

  void 'test waf input truncated metrics'() {
    given:
    def collector = WafMetricCollector.get()
    def bitField = WafMetricCollector.computeWafInputTruncatedIndex(stringTooLong, listMapTooLarge, objectTooDeep)

    when:
    collector.wafInputTruncated(stringTooLong, listMapTooLarge, objectTooDeep)

    then:
    collector.prepareMetrics()
    def metrics = collector.drain()
    def inputTruncatedMetrics = metrics.findAll { it.metricName == 'waf.input_truncated' }

    final metric = inputTruncatedMetrics[0]
    metric.type == 'count'
    metric.metricName == 'waf.input_truncated'
    metric.namespace == 'appsec'
    metric.tags == ["truncation_reason:${bitField}"]

    where:
    [stringTooLong, listMapTooLarge, objectTooDeep] << allBooleanCombinations(3)
  }

  void 'test waf config error metrics'() {
    given:
    def collector = WafMetricCollector.get()

    when:
    collector.wafInit('waf_ver1', 'rules.1', true)
    collector.addWafConfigError(5)
    collector.addWafConfigError(3)
    collector.prepareMetrics()

    then:
    def metrics = collector.drain()
    def configErrorMetrics = metrics.findAll { it.metricName == 'waf.config_errors' }

    final metric = configErrorMetrics[0]
    metric.type == 'count'
    metric.metricName == 'waf.config_errors'
    metric.namespace == 'appsec'
    metric.value == 8
    metric.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.1'].toSet()
  }

  void 'test ai guard request'() {
    given:
    final collector = WafMetricCollector.get()

    when:
    collector.aiGuardRequest(action, block)

    then:
    collector.prepareMetrics()
    final metrics = collector.drain()
    final configErrorMetrics = metrics.findAll { it.metricName == 'ai_guard.requests' }

    final metric = configErrorMetrics[0]
    metric.type == 'count'
    metric.metricName == 'ai_guard.requests'
    metric.namespace == 'appsec'
    metric.value == 1
    metric.tags.toSet() == ["action:${action.name()}", "block:${block}", 'error:false'].toSet()

    where:
    action | block
    ALLOW  | true
    ALLOW  | false
    DENY   | true
    DENY   | false
    ABORT  | true
    ABORT  | false
  }

  void 'test ai guard error'() {
    given:
    final collector = WafMetricCollector.get()

    when:
    collector.aiGuardError()

    then:
    collector.prepareMetrics()
    final metrics = collector.drain()
    final configErrorMetrics = metrics.findAll { it.metricName == 'ai_guard.requests' }

    final metric = configErrorMetrics[0]
    metric.type == 'count'
    metric.metricName == 'ai_guard.requests'
    metric.namespace == 'appsec'
    metric.value == 1
    metric.tags.toSet() == ['error:true'].toSet()
  }

  void 'test ai guard truncated'() {
    given:
    final collector = WafMetricCollector.get()

    when:
    collector.aiGuardTruncated(type)

    then:
    collector.prepareMetrics()
    final metrics = collector.drain()
    final configErrorMetrics = metrics.findAll { it.metricName == 'ai_guard.truncated' }

    final metric = configErrorMetrics[0]
    metric.type == 'count'
    metric.metricName == 'ai_guard.truncated'
    metric.namespace == 'appsec'
    metric.value == 1
    metric.tags.toSet() == ["type:${type.tagValue}"].toSet()

    where:
    type << [MESSAGES, CONTENT]
  }

  /**
   * Helper method to generate all combinations of n boolean values.
   */
  static List<List<Boolean>> allBooleanCombinations(int n) {
    int total = 1 << n
    def combinations = []
    for (int i = 0; i < total; i++) {
      def combo = []
      for (int j = 0; j < n; j++) {
        combo << (((i >> j) & 1) == 1)
      }
      combinations << combo
    }
    return combinations
  }
}
