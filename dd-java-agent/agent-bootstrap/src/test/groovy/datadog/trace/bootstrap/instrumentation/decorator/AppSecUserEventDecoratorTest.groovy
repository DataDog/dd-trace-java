package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING

class AppSecUserEventDecoratorTest extends DDSpecification {

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
    injectSysConfig(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, mode)
    def decorator = newDecorator()

    when:
    decorator.onSignup('user', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.signup.auto.mode', modeTag)
    1 * traceSegment.setTagTop('appsec.events.users.signup.track', true, true)
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop('usr.id', 'user')
    1 * traceSegment.setTagTop('appsec.events.users.signup', ['key1':'value1', 'key2':'value2'])
    0 * _

    where:
    mode        | modeTag
    'safe'      | 'SAFE'
    'extended'  | 'EXTENDED'
  }

  def "test onLoginSuccess [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, mode)
    def decorator = newDecorator()

    when:
    decorator.onLoginSuccess('user', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.auto.mode', modeTag)
    1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true, true)
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop('usr.id', 'user')
    1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1':'value1', 'key2':'value2'])
    0 * _

    where:
    mode        | modeTag
    'safe'      | 'SAFE'
    'extended'  | 'EXTENDED'
  }

  def "test onLoginFailed #description [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, mode)
    def decorator = newDecorator()

    when:
    decorator.onLoginFailure('user', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', modeTag)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, true)
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.id', 'user')
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1':'value1', 'key2':'value2'])
    0 * _

    where:
    mode       | modeTag    | description
    'safe'     | 'SAFE'     | 'with existing user'
    'safe'     | 'SAFE'     | 'user doesn\'t exist'
    'extended' | 'EXTENDED' | 'with existing user'
    'extended' | 'EXTENDED' | 'user doesn\'t exist'
  }

  def "test onUserNotFound [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, mode)
    def decorator = newDecorator()

    when:
    decorator.onUserNotFound()

    then:
    1 * traceSegment.setTagTop("appsec.events.users.login.failure.usr.exists", false)
    0 * _

    where:
    mode       | modeTag
    'safe'     | 'SAFE'
    'extended' | 'EXTENDED'
  }

  def "test isEnabled (appsec = #appsec, mode = #mode)"() {
    setup:
    ActiveSubsystems.APPSEC_ACTIVE = appsec
    injectSysConfig(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, mode)
    def decorator = newDecorator()

    when:
    def enabled = decorator.isEnabled()

    then:
    enabled == result

    where:
    appsec | mode       | result
    false  | "disabled" | false
    false  | "safe"     | false
    false  | "extended" | false
    true   | "disabled" | false
    true   | "safe"     | true
    true   | "extended" | true
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
