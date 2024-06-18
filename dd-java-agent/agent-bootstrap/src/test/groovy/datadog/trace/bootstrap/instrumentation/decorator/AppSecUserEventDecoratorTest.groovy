package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.Config
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.UserEventTrackingMode.DISABLED
import static datadog.trace.api.UserEventTrackingMode.EXTENDED
import static datadog.trace.api.UserEventTrackingMode.SAFE
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
    decorator.onSignup(user, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.signup.auto.mode', mode)
    1 * traceSegment.setTagTop('appsec.events.users.signup.track', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    if (setUser) {
      1 * traceSegment.setTagTop('usr.id', user)
    }
    1 * traceSegment.setTagTop('appsec.events.users.signup', ['key1': 'value1', 'key2': 'value2'])
    0 * _

    where:
    mode       | user                                   | setUser
    'safe'     | 'user'                                 | false
    'safe'     | '1234'                                 | true
    'safe'     | '591dc126-8431-4d0f-9509-b23318d3dce4' | true
    'extended' | 'user'                                 | true
    'extended' | '1234'                                 | true
    'extended' | '591dc126-8431-4d0f-9509-b23318d3dce4' | true
  }

  def "test onLoginSuccess [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, mode)
    def decorator = newDecorator()

    when:
    decorator.onLoginSuccess(user, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.auto.mode', mode)
    1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    if (setUser) {
      1 * traceSegment.setTagTop('usr.id', user)
    }
    1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1': 'value1', 'key2': 'value2'])
    0 * _

    where:
    mode       | user                                   | setUser
    'safe'     | 'user'                                 | false
    'safe'     | '1234'                                 | true
    'safe'     | '591dc126-8431-4d0f-9509-b23318d3dce4' | true
    'extended' | 'user'                                 | true
    'extended' | '1234'                                 | true
    'extended' | '591dc126-8431-4d0f-9509-b23318d3dce4' | true
  }

  def "test onLoginFailed [#mode]"() {
    setup:
    injectSysConfig(APPSEC_AUTOMATED_USER_EVENTS_TRACKING, mode)
    def decorator = newDecorator()

    when:
    decorator.onLoginFailure(user, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', mode)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    if (setUser) {
      1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.id', user)
    }
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1': 'value1', 'key2': 'value2'])
    0 * _

    where:
    mode       | user                                   | setUser
    'safe'     | 'user'                                 | false
    'safe'     | '1234'                                 | true
    'safe'     | '591dc126-8431-4d0f-9509-b23318d3dce4' | true
    'extended' | 'user'                                 | true
    'extended' | '1234'                                 | true
    'extended' | '591dc126-8431-4d0f-9509-b23318d3dce4' | true
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
    mode << ['safe', 'extended']
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

    and:
    Config.get().getAppSecUserEventsTrackingMode() == expectedMode

    where:
    appsec | mode       | result | expectedMode
    false  | "disabled" | false  | DISABLED
    false  | "safe"     | false  | SAFE
    false  | "1"        | false  | SAFE
    false  | "true"     | false  | SAFE
    false  | "extended" | false  | EXTENDED
    true   | "disabled" | false  | DISABLED
    true   | "safe"     | true   | SAFE
    true   | "1"        | true   | SAFE
    true   | "true"     | true   | SAFE
    true   | "extended" | true   | EXTENDED
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
