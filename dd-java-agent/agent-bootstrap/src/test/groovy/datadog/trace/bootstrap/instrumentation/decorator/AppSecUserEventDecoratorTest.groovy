package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.internal.TraceSegment
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.config.AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING

class AppSecUserEventDecoratorTest extends DDSpecification {

  def traceSegment = Mock(TraceSegment)

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
    decorator.onLoginFailure('user', ['key1': 'value1', 'key2': 'value2'], (boolean)userExists)

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', modeTag)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, true)
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.id', 'user')
    1 * traceSegment.setTagTop("appsec.events.users.login.failure.usr.exists", userExists)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1':'value1', 'key2':'value2'])
    0 * _

    where:
    mode        | modeTag       | userExists    | description
    'safe'      | 'SAFE'        | true          | 'with existing user'
    'safe'      | 'SAFE'        | false         | 'user doesn\'t exist'
    'extended'  | 'EXTENDED'    | true          | 'with existing user'
    'extended'  | 'EXTENDED'    | false         | 'user doesn\'t exist'
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
