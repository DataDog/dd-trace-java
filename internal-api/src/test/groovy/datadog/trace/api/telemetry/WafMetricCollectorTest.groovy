package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WafMetricCollectorTest extends DDSpecification {

  public static final int DD_WAF_RUN_INTERNAL_ERROR = -3
  public static final int DD_WAF_RUN_INVALID_OBJECT_ERROR = -2

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
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequest()
    WafMetricCollector.get().wafRequestTriggered()
    WafMetricCollector.get().wafRequestBlocked()
    WafMetricCollector.get().wafRequestTimeout()
    WafMetricCollector.get().wafRequestError()
    WafMetricCollector.get().raspRuleEval(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspRuleEval(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspRuleMatch(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspRuleEval(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspTimeout(RuleType.SQL_INJECTION)
    WafMetricCollector.get().raspErrorCode(RuleType.SHELL_INJECTION, DD_WAF_RUN_INTERNAL_ERROR)
    WafMetricCollector.get().wafErrorCode(RuleType.SHELL_INJECTION, DD_WAF_RUN_INTERNAL_ERROR)
    WafMetricCollector.get().raspErrorCode(RuleType.SQL_INJECTION, DD_WAF_RUN_INVALID_OBJECT_ERROR)
    WafMetricCollector.get().wafErrorCode(RuleType.SQL_INJECTION, DD_WAF_RUN_INVALID_OBJECT_ERROR)

    WafMetricCollector.get().prepareMetrics()

    then:
    def metrics = WafMetricCollector.get().drain()

    def initMetric = (WafMetricCollector.WafInitRawMetric)metrics[0]
    initMetric.type == 'count'
    initMetric.value == 1
    initMetric.namespace == 'appsec'
    initMetric.metricName == 'waf.init'
    initMetric.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.1', 'success:true'].toSet()

    def updateMetric1 = (WafMetricCollector.WafUpdatesRawMetric)metrics[1]
    updateMetric1.type == 'count'
    updateMetric1.value == 1
    updateMetric1.namespace == 'appsec'
    updateMetric1.metricName == 'waf.updates'
    updateMetric1.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.2', 'success:true'].toSet()

    def updateMetric2 = (WafMetricCollector.WafUpdatesRawMetric)metrics[2]
    updateMetric2.type == 'count'
    updateMetric2.value == 2
    updateMetric2.namespace == 'appsec'
    updateMetric2.metricName == 'waf.updates'
    updateMetric2.tags.toSet() == ['waf_version:waf_ver1', 'event_rules_version:rules.3', 'success:false'].toSet()

    def requestMetric = (WafMetricCollector.WafRequestsRawMetric)metrics[3]
    requestMetric.namespace == 'appsec'
    requestMetric.metricName == 'waf.requests'
    requestMetric.type == 'count'
    requestMetric.value == 3
    requestMetric.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'rule_triggered:false',
      'request_blocked:false',
      'waf_error:false',
      'waf_timeout:false'
    ].toSet()

    def requestTriggeredMetric = (WafMetricCollector.WafRequestsRawMetric)metrics[4]
    requestTriggeredMetric.namespace == 'appsec'
    requestTriggeredMetric.metricName == 'waf.requests'
    requestTriggeredMetric.value == 1
    requestTriggeredMetric.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'rule_triggered:true',
      'request_blocked:false',
      'waf_error:false',
      'waf_timeout:false'
    ].toSet()


    def requestBlockedMetric = (WafMetricCollector.WafRequestsRawMetric)metrics[5]
    requestBlockedMetric.namespace == 'appsec'
    requestBlockedMetric.metricName == 'waf.requests'
    requestBlockedMetric.type == 'count'
    requestBlockedMetric.value == 1
    requestBlockedMetric.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'rule_triggered:true',
      'request_blocked:true',
      'waf_error:false',
      'waf_timeout:false'
    ].toSet()

    def requestTimeoutMetric = (WafMetricCollector.WafRequestsRawMetric)metrics[6]
    requestTimeoutMetric.namespace == 'appsec'
    requestTimeoutMetric.metricName == 'waf.requests'
    requestTimeoutMetric.type == 'count'
    requestTimeoutMetric.value == 1
    requestTimeoutMetric.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'rule_triggered:false',
      'request_blocked:false',
      'waf_error:false',
      'waf_timeout:true'
    ].toSet()

    def requestWafErrorMetric = (WafMetricCollector.WafRequestsRawMetric)metrics[7]
    requestWafErrorMetric.namespace == 'appsec'
    requestWafErrorMetric.metricName == 'waf.requests'
    requestWafErrorMetric.type == 'count'
    requestWafErrorMetric.value == 1
    requestWafErrorMetric.tags.toSet() == [
      'waf_version:waf_ver1',
      'event_rules_version:rules.3',
      'rule_triggered:false',
      'request_blocked:false',
      'waf_error:true',
      'waf_timeout:false'
    ].toSet()

    def raspRuleEvalSqli = (WafMetricCollector.RaspRuleEval)metrics[8]
    raspRuleEvalSqli.type == 'count'
    raspRuleEvalSqli.value == 3
    raspRuleEvalSqli.namespace == 'appsec'
    raspRuleEvalSqli.metricName == 'rasp.rule.eval'
    raspRuleEvalSqli.tags.toSet() == ['rule_type:sql_injection', 'waf_version:waf_ver1'].toSet()

    def raspRuleMatch = (WafMetricCollector.RaspRuleMatch)metrics[9]
    raspRuleMatch.type == 'count'
    raspRuleMatch.value == 1
    raspRuleMatch.namespace == 'appsec'
    raspRuleMatch.metricName == 'rasp.rule.match'
    raspRuleMatch.tags.toSet() == ['rule_type:sql_injection', 'waf_version:waf_ver1'].toSet()

    def raspTimeout = (WafMetricCollector.RaspTimeout)metrics[10]
    raspTimeout.type == 'count'
    raspTimeout.value == 1
    raspTimeout.namespace == 'appsec'
    raspTimeout.metricName == 'rasp.timeout'
    raspTimeout.tags.toSet() == ['rule_type:sql_injection', 'waf_version:waf_ver1'].toSet()

    def raspInvalidCode = (WafMetricCollector.RaspError)metrics[10]
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

    def wafInvalidCode = (WafMetricCollector.WafError)metrics[11]
    wafInvalidCode.type == 'count'
    wafInvalidCode.value == 1
    wafInvalidCode.namespace == 'appsec'
    wafInvalidCode.metricName == 'waf.error'
    wafInvalidCode.tags.toSet() == [
      'waf_version:waf_ver1',
      'rule_type:command_injection',
      'rule_variant:shell',
      'event_rules_version:rules.3',
      'waf_error:' +DD_WAF_RUN_INTERNAL_ERROR
    ].toSet()

    def raspInvalidObjectCode = (WafMetricCollector.RaspError)metrics[12]
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

    def wafInvalidObjectCode = (WafMetricCollector.WafError)metrics[13]
    wafInvalidObjectCode.type == 'count'
    wafInvalidObjectCode.value == 1
    wafInvalidObjectCode.namespace == 'appsec'
    wafInvalidObjectCode.metricName == 'waf.error'
    wafInvalidObjectCode.tags.toSet() == [
      'rule_type:sql_injection',
      'waf_version:waf_ver1',
      'waf_error:'+DD_WAF_RUN_INVALID_OBJECT_ERROR
    ].toSet()
  }

  def "overflowing WafMetricCollector does not crash"() {
    given:
    final limit = 1024
    def collector = WafMetricCollector.get()

    when:
    (0..limit*2).each {
      collector.wafInit("foo", "bar", true)
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit*2).each {
      collector.wafUpdates("bar", true)
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit*2).each {
      collector.wafRequest()
      collector.prepareMetrics()
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit*2).each {
      collector.wafRequestTriggered()
      collector.prepareMetrics()
    }

    then:
    noExceptionThrown()
    collector.drain().size() == limit

    when:
    (0..limit*2).each {
      collector.wafRequestBlocked()
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
        action.call(LoginFramework.SPRING_SECURITY, LoginEvent.LOGIN_SUCCESS)
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
        case LoginEvent.LOGIN_SUCCESS.getTag():
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
    WafMetricCollector.get().wafErrorCode(ruleType, DD_WAF_RUN_INTERNAL_ERROR)
    WafMetricCollector.get().prepareMetrics()

    then:
    def metrics = WafMetricCollector.get().drain()

    def raspRuleEval = (WafMetricCollector.RaspRuleEval)metrics[1]
    raspRuleEval.type == 'count'
    raspRuleEval.value == 3
    raspRuleEval.namespace == 'appsec'
    raspRuleEval.metricName == 'rasp.rule.eval'
    raspRuleEval.tags.toSet() == [
      'rule_type:command_injection',
      'rule_variant:'+ruleType.variant,
      'waf_version:waf_ver1',
      'event_rules_version:rules.1'
    ].toSet()

    def raspRuleMatch = (WafMetricCollector.RaspRuleMatch)metrics[2]
    raspRuleMatch.type == 'count'
    raspRuleMatch.value == 1
    raspRuleMatch.namespace == 'appsec'
    raspRuleMatch.metricName == 'rasp.rule.match'
    raspRuleMatch.tags.toSet() == [
      'rule_type:command_injection',
      'rule_variant:'+ruleType.variant,
      'waf_version:waf_ver1',
      'event_rules_version:rules.1'
    ].toSet()

    def raspTimeout = (WafMetricCollector.RaspTimeout)metrics[3]
    raspTimeout.type == 'count'
    raspTimeout.value == 1
    raspTimeout.namespace == 'appsec'
    raspTimeout.metricName == 'rasp.timeout'
    raspTimeout.tags.toSet() == [
      'rule_type:command_injection',
      'rule_variant:'+ruleType.variant,
      'waf_version:waf_ver1',
      'event_rules_version:rules.1'
    ].toSet()

    def raspInvalidCode = (WafMetricCollector.RaspError)metrics[4]
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

    def wafInvalidCode = (WafMetricCollector.WafError)metrics[5]
    wafInvalidCode.type == 'count'
    wafInvalidCode.value == 1
    wafInvalidCode.namespace == 'appsec'
    wafInvalidCode.metricName == 'waf.error'
    wafInvalidCode.tags.toSet() == [
      'waf_version:waf_ver1',
      'rule_type:command_injection',
      'rule_variant:' + ruleType.variant,
      'event_rules_version:rules.1',
      'waf_error:' + DD_WAF_RUN_INTERNAL_ERROR
    ].toSet()

    where:
    ruleType << [RuleType.COMMAND_INJECTION, RuleType.SHELL_INJECTION]
  }
}
