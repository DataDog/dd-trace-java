package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING
import static datadog.trace.api.config.AppSecConfig.APPSEC_AUTO_USER_INSTRUMENTATION_MODE

class AppSecUserEventDecoratorTest extends DDSpecification {

  private static final String USER_ID = 'user'
  private static final String ANONYMIZED_USER_ID = 'anon_04f8996da763b7a969b1028ee3007569'

  def traceSegment = Mock(TraceSegment)

  Boolean appsecActiveOriginal = null

  def setup() {
    appsecActiveOriginal = ActiveSubsystems.APPSEC_ACTIVE
    ActiveSubsystems.APPSEC_ACTIVE = true
  }

  def cleanup() {
    ActiveSubsystems.APPSEC_ACTIVE = appsecActiveOriginal
  }

  def "test onSignup [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, mode)
    def decorator = newDecorator()

    when:
    decorator.onSignup(USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.signup.auto.mode', modeTag)
    1 * traceSegment.setTagTop('appsec.events.users.signup.track', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.getTagTop('usr.id') >> null
    1 * traceSegment.setTagTop('usr.id', expectedUserId)
    1 * traceSegment.setTagTop('appsec.events.users.signup', ['key1': 'value1', 'key2': 'value2'])
    0 * _

    where:
    mode    | modeTag          | expectedUserId
    'anon'  | 'anonymization'  | ANONYMIZED_USER_ID
    'ident' | 'identification' | USER_ID
  }

  def "test onLoginSuccess [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, mode)
    def decorator = newDecorator()

    when:
    decorator.onLoginSuccess(USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.auto.mode', modeTag)
    1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.getTagTop('usr.id') >> null
    1 * traceSegment.setTagTop('usr.id', expectedUserId)
    1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1': 'value1', 'key2': 'value2'])
    0 * _

    where:
    mode    | modeTag          | expectedUserId
    'anon'  | 'anonymization'  | ANONYMIZED_USER_ID
    'ident' | 'identification' | USER_ID
  }

  def "test onLoginFailed #description [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, mode)
    def decorator = newDecorator()

    when:
    decorator.onLoginFailure(USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', modeTag)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.getTagTop('appsec.events.users.login.failure.usr.id') >> null
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.id', expectedUserId)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1': 'value1', 'key2': 'value2'])
    0 * _

    where:
    mode    | modeTag          | description           | expectedUserId
    'anon'  | 'anonymization'  | 'with existing user'  | ANONYMIZED_USER_ID
    'anon'  | 'anonymization'  | 'user doesn\'t exist' | ANONYMIZED_USER_ID
    'ident' | 'identification' | 'with existing user'  | USER_ID
    'ident' | 'identification' | 'user doesn\'t exist' | USER_ID
  }

  def "test onUserNotFound [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, mode)
    def decorator = newDecorator()

    when:
    decorator.onUserNotFound()

    then:
    1 * traceSegment.setTagTop("appsec.events.users.login.failure.usr.exists", false)
    0 * _

    where:
    mode    | modeTag
    'anon'  | 'ANONYMIZATION'
    'ident' | 'IDENTIFIED'
  }

  def "test isEnabled (appsec = #appsec, tracking = #trackingMode, collection = #collectionMode)"() {
    setup:
    ActiveSubsystems.APPSEC_ACTIVE = appsec
    final addConfig = (String name, String value) -> {
      if (value) {
        injectSysConfig(name, value)
      } else {
        removeSysConfig(name)
      }
    }
    addConfig(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, trackingMode)
    addConfig(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, collectionMode)
    def decorator = newDecorator()

    when:
    def enabled = decorator.isEnabled()

    then:
    enabled == result

    where:
    appsec | collectionMode | trackingMode | result
    // disabled states
    false  | null           | null         | false
    false  | null           | 'safe'       | false
    false  | null           | 'extended'   | false
    false  | null           | 'disabled'   | false
    false  | 'ident'        | null         | false
    false  | 'ident'        | 'safe'       | false
    false  | 'ident'        | 'extended'   | false
    false  | 'ident'        | 'disabled'   | false
    false  | 'anon'         | null         | false
    false  | 'anon'         | 'safe'       | false
    false  | 'anon'         | 'extended'   | false
    false  | 'anon'         | 'disabled'   | false
    false  | 'disabled'     | null         | false
    false  | 'disabled'     | 'safe'       | false
    false  | 'disabled'     | 'extended'   | false
    false  | 'disabled'     | 'disabled'   | false
    true   | null           | 'disabled'   | false
    true   | 'disabled'     | null         | false
    true   | 'disabled'     | 'safe'       | false
    true   | 'disabled'     | 'extended'   | false
    true   | 'disabled'     | 'disabled'   | false

    // enabled states
    true   | null           | null         | true
    true   | null           | 'safe'       | true
    true   | null           | 'extended'   | true
    true   | 'ident'        | null         | true
    true   | 'ident'        | 'safe'       | true
    true   | 'ident'        | 'extended'   | true
    true   | 'ident'        | 'disabled'   | true
    true   | 'anon'         | null         | true
    true   | 'anon'         | 'safe'       | true
    true   | 'anon'         | 'extended'   | true
    true   | 'anon'         | 'disabled'   | true
  }

  void 'test missing user id callback'() {
    setup:
    final collector = WafMetricCollector.get()
    collector.prepareMetrics()
    collector.drain()
    injectSysConfig(APPSEC_AUTO_USER_INSTRUMENTATION_MODE, 'ident')
    def decorator = newDecorator()

    when:
    decorator.onLoginSuccess(null, [:])

    then:
    collector.prepareMetrics()
    final metrics = collector.drain()
    metrics.size() == 1
    final metric = metrics.first()
    metric.namespace == 'appsec'
    metric.type == 'count'
    metric.metricName == 'instrum.user_auth.missing_user_id'
    metric.value == 1
    0 * _
  }

  void 'test user id anonymization of #userId'() {
    when:
    final anonymized = AppSecUserEventDecorator.anonymize(userId)

    then:
    anonymized == expected

    where:
    userId                  | expected
    null                    | null
    'zouzou@sansgluten.com' | 'anon_0c76692372ebf01a7da6e9570fb7d0a1'
  }

  def newDecorator() {
    return new AppSecUserEventDecorator() {
      @Override
      protected TraceSegment getSegment() {
        return traceSegment
      }
    }
  }
}
