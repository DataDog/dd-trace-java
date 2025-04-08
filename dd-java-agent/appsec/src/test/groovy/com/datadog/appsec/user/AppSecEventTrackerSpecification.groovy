package com.datadog.appsec.user

import com.datadog.appsec.gateway.NoopFlow
import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.appsec.api.login.EventTrackerV2
import datadog.appsec.api.user.User
import datadog.trace.api.EventTracker
import datadog.trace.api.GlobalTracer
import datadog.trace.api.ProductTraceSource
import datadog.trace.api.UserIdCollectionMode
import datadog.trace.api.appsec.AppSecEventTracker
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.telemetry.LoginEvent
import datadog.trace.api.telemetry.LoginVersion
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import java.util.function.BiFunction

import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION
import static datadog.trace.api.UserIdCollectionMode.DISABLED
import static datadog.trace.api.UserIdCollectionMode.IDENTIFICATION
import static datadog.trace.api.UserIdCollectionMode.SDK
import static datadog.trace.api.gateway.Events.EVENTS
import static datadog.trace.api.telemetry.LoginEvent.CUSTOM
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS
import static datadog.trace.api.telemetry.LoginEvent.SIGN_UP
import static datadog.appsec.api.user.User.setUser
import static datadog.trace.api.telemetry.LoginVersion.V1
import static datadog.trace.api.telemetry.LoginVersion.V2

class AppSecEventTrackerSpecification extends DDSpecification {

  private static final String USER_LOGIN = 'user'
  private static final String ANONYMIZED_USER_LOGIN = 'anon_04f8996da763b7a969b1028ee3007569'
  private static final String USER_ID = '1'
  private static final String ANONYMIZED_USER_ID = 'anon_6b86b273ff34fce19d6b804eff5a3f57'

  @Shared
  private static boolean appSecActiveBefore = ActiveSubsystems.APPSEC_ACTIVE
  @Shared
  private static EventTracker eventTrackerBefore = GlobalTracer.getEventTracker()

  private AppSecEventTracker tracker
  private TraceSegment traceSegment
  private TracerAPI tracer
  private AgentSpan span
  private CallbackProvider provider
  private BiFunction<RequestContext, String, Flow<Void>> user
  private TriFunction<RequestContext, LoginEvent, String, Flow<Void>> loginEvent

  void setup() {
    traceSegment = Mock(TraceSegment)
    span = Stub(AgentSpan)
    user = Mock(BiFunction)
    loginEvent = Mock(TriFunction)

    provider = Stub(CallbackProvider) {
      getCallback(EVENTS.user()) >> user
      getCallback(EVENTS.loginEvent()) >> loginEvent
    }
    tracer = Stub(TracerAPI) {
      getTraceSegment() >> traceSegment
      activeSpan() >> span
      getCallbackProvider(RequestContextSlot.APPSEC) >> provider
    }
    tracker = new AppSecEventTracker() {
        @Override
        protected TracerAPI tracer() {
          return tracer
        }
      }
    GlobalTracer.setEventTracker(tracker)
    User.setUserService(tracker)
    EventTrackerV2.setEventTrackerService(tracker)
    ActiveSubsystems.APPSEC_ACTIVE = true
  }

  void cleanupSpec() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActiveBefore
    GlobalTracer.setEventTracker(eventTrackerBefore)
  }

  def 'test track login success event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent(USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('usr.id', USER_ID)
    1 * traceSegment.setTagTop('appsec.events.users.login.success.usr.login', USER_ID, true)
    1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1': 'value1', 'key2': 'value2'], true)
    1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true, true)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.sdk', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
    1 * loginEvent.apply(_ as RequestContext, LOGIN_SUCCESS, USER_ID) >> NoopFlow.INSTANCE
    1 * user.apply(_ as RequestContext, USER_ID) >> NoopFlow.INSTANCE
    0 * _

    assertAppSecSdkEvent(LOGIN_SUCCESS, V1)
  }

  def 'test track login failure event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent(USER_ID, true, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.id', USER_ID, true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.login', USER_ID, true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.exists', true, true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1': 'value1', 'key2': 'value2'], true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, true)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.sdk', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
    1 * loginEvent.apply(_ as RequestContext, LOGIN_FAILURE, USER_ID) >> NoopFlow.INSTANCE
    1 * user.apply(_ as RequestContext, USER_ID) >> NoopFlow.INSTANCE
    0 * _

    assertAppSecSdkEvent(LOGIN_FAILURE, V1)
  }

  def 'test track custom event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackCustomEvent('myevent', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.myevent', ['key1': 'value1', 'key2': 'value2'], true)
    1 * traceSegment.setTagTop('appsec.events.myevent.track', true, true)
    1 * traceSegment.setTagTop('_dd.appsec.events.myevent.sdk', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
    0 * _

    assertAppSecSdkEvent(CUSTOM, V2)
  }

  def 'test track login success event V2 (SDK)'() {
    when:
    EventTrackerV2.trackUserLoginSuccess(USER_LOGIN, USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('usr.id', USER_ID)
    1 * traceSegment.setTagTop('usr', ['key1': 'value1', 'key2': 'value2'])
    1 * traceSegment.setTagTop('appsec.events.users.login.success.usr.id', USER_ID, true)
    1 * traceSegment.setTagTop('appsec.events.users.login.success.usr', ['key1': 'value1', 'key2': 'value2'], true)
    1 * traceSegment.setTagTop('_dd.appsec.user.collection_mode', 'sdk')
    1 * traceSegment.setTagTop('appsec.events.users.login.success.usr.login', USER_LOGIN, true)
    1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1': 'value1', 'key2': 'value2'], true)
    1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true, true)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.sdk', true, true)
    2 * traceSegment.setTagTop('asm.keep', true)
    2 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
    1 * loginEvent.apply(_ as RequestContext, LOGIN_SUCCESS, USER_LOGIN) >> NoopFlow.INSTANCE
    1 * user.apply(_ as RequestContext, USER_ID) >> NoopFlow.INSTANCE
    0 * _

    assertAppSecSdkEvent(LOGIN_SUCCESS, V2)
  }

  def 'test track login failure event V2 (SDK)'() {
    when:
    EventTrackerV2.trackUserLoginFailure(USER_LOGIN, true, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.login', USER_LOGIN, true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.exists', true, true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1': 'value1', 'key2': 'value2'], true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, true)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.sdk', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
    1 * loginEvent.apply(_ as RequestContext, LOGIN_FAILURE, USER_LOGIN) >> NoopFlow.INSTANCE
    0 * _

    assertAppSecSdkEvent(LOGIN_FAILURE, V2)
  }

  def 'test track custom event V2 (SDK)'() {
    when:
    EventTrackerV2.trackCustomEvent('myevent', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.myevent', ['key1': 'value1', 'key2': 'value2'], true)
    1 * traceSegment.setTagTop('appsec.events.myevent.track', true, true)
    1 * traceSegment.setTagTop('_dd.appsec.events.myevent.sdk', true, true)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
    0 * _
  }

  def 'test track user (SDK)'() {
    when:
    setUser(USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('usr.id', USER_ID)
    1 * traceSegment.setTagTop('usr', ['key1': 'value1', 'key2': 'value2'])
    1 * traceSegment.setTagTop('_dd.appsec.user.collection_mode', SDK.fullName())
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
    1 * user.apply(_ as RequestContext, USER_ID) >> NoopFlow.INSTANCE
    0 * _
  }

  def 'test wrong event argument validation (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent(null, null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent(null, false, null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackCustomEvent(null, null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent('', null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent('', false, null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackCustomEvent('', null)

    then:
    thrown IllegalArgumentException

    when:
    setUser(null, null)

    then:
    thrown IllegalArgumentException
  }

  def "test onSignup (#mode)"() {
    setup:
    final expectedUserLogin = mode == ANONYMIZATION ? ANONYMIZED_USER_LOGIN : USER_LOGIN

    when:
    tracker.onSignupEvent(mode, USER_LOGIN, ['key1': 'value1', 'key2': 'value2'])

    then:
    if (mode != DISABLED) {
      1 * traceSegment.getTagTop('_dd.appsec.events.users.signup.sdk') >> null // no SDK event before
      1 * traceSegment.setTagTop('_dd.appsec.usr.login', expectedUserLogin)
      1 * traceSegment.setTagTop('_dd.appsec.events.users.signup.auto.mode', mode.fullName(), true)
      1 * traceSegment.setTagTop('appsec.events.users.signup.usr.login', expectedUserLogin, true)
      1 * traceSegment.setTagTop('appsec.events.users.signup', ['key1': 'value1', 'key2': 'value2'], true)
      1 * traceSegment.setTagTop('appsec.events.users.signup.track', true, true)
      1 * traceSegment.setTagTop('asm.keep', true)
      1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
      1 * loginEvent.apply(_ as RequestContext, SIGN_UP, expectedUserLogin) >> NoopFlow.INSTANCE
    }
    0 * _

    where:
    mode << [IDENTIFICATION, ANONYMIZATION, DISABLED]
  }

  def "test onLoginSuccess (#mode)"() {
    setup:
    final expectedUserLogin = mode == ANONYMIZATION ? ANONYMIZED_USER_LOGIN : USER_LOGIN

    when:
    tracker.onLoginSuccessEvent(mode, USER_LOGIN, ['key1': 'value1', 'key2': 'value2'])

    then:
    if (mode != DISABLED) {
      1 * traceSegment.getTagTop('_dd.appsec.events.users.login.success.sdk') >> null // no SDK event before
      1 * traceSegment.setTagTop('_dd.appsec.usr.login', expectedUserLogin)
      1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.auto.mode', mode.fullName(), true)
      1 * traceSegment.setTagTop('appsec.events.users.login.success.usr.login', expectedUserLogin, true)
      1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1': 'value1', 'key2': 'value2'], true)
      1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true, true)
      1 * traceSegment.setTagTop('asm.keep', true)
      1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
      1 * loginEvent.apply(_ as RequestContext, LOGIN_SUCCESS, expectedUserLogin) >> NoopFlow.INSTANCE
    }
    0 * _

    where:
    mode << [IDENTIFICATION, ANONYMIZATION, DISABLED]
  }

  def "test onLoginFailed (#mode)"() {
    setup:
    final expectedUserLogin = mode == ANONYMIZATION ? ANONYMIZED_USER_LOGIN : USER_LOGIN

    when:
    tracker.onLoginFailureEvent(mode, USER_LOGIN, true, ['key1': 'value1', 'key2': 'value2'])

    then:
    if (mode != DISABLED) {
      1 * traceSegment.getTagTop('_dd.appsec.events.users.login.failure.sdk') >> null // no SDK event before
      1 * traceSegment.setTagTop('_dd.appsec.usr.login', expectedUserLogin)
      1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', mode.fullName(), true)
      1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.login', expectedUserLogin, true)
      1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1': 'value1', 'key2': 'value2'], true)
      1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.exists', true, true)
      1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, true)
      1 * traceSegment.setTagTop('asm.keep', true)
      1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
      1 * loginEvent.apply(_ as RequestContext, LOGIN_FAILURE, expectedUserLogin) >> NoopFlow.INSTANCE
    }
    0 * _

    where:
    mode << [IDENTIFICATION, ANONYMIZATION, DISABLED]
  }

  def "test onUserEvent (#mode)"() {
    setup:
    final expectedUserId = mode == ANONYMIZATION ? ANONYMIZED_USER_ID : USER_ID

    when:
    tracker.onUserEvent(mode, USER_ID, [:])

    then:
    if (mode != DISABLED) {
      1 * traceSegment.setTagTop('_dd.appsec.usr.id', expectedUserId)
      1 * traceSegment.getTagTop('_dd.appsec.user.collection_mode') >> null // no user event before
      1 * traceSegment.setTagTop('_dd.appsec.user.collection_mode', mode.fullName())
      1 * traceSegment.setTagTop('usr.id', expectedUserId)
      1 * traceSegment.setTagTop('asm.keep', true)
      1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
      1 * user.apply(_ as RequestContext, expectedUserId) >> NoopFlow.INSTANCE
    }
    0 * _

    where:
    mode << [IDENTIFICATION, ANONYMIZATION, DISABLED]
  }

  def "test onUserNotFound (#mode)"() {
    when:
    tracker.onUserNotFound(mode)

    then:
    if (mode != DISABLED) {
      1 * traceSegment.getTagTop('_dd.appsec.events.users.login.failure.sdk') >> null // no SDK event before
      1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', mode.fullName(), true)
      1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.exists', false, true)
      1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, true)
      1 * traceSegment.setTagTop('asm.keep', true)
      1 * traceSegment.setTagTop('_dd.p.ts', ProductTraceSource.ASM)
    }
    0 * _

    where:
    mode << [IDENTIFICATION, ANONYMIZATION, DISABLED]
  }

  def "test isEnabled (appsec = #appsec, tracking = #trackingMode, collection = #collectionMode)"() {
    setup:
    ActiveSubsystems.APPSEC_ACTIVE = appsec
    final mode = UserIdCollectionMode.fromString(collectionMode, trackingMode)

    when:
    def enabled = tracker.isEnabled(mode)

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

  void 'test blocking on a login'() {
    setup:
    final action = new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO)
    loginEvent.apply(_ as RequestContext, LOGIN_SUCCESS, USER_LOGIN) >> new ActionFlow<Void>(action: action)

    when:
    tracker.onLoginSuccessEvent(SDK, USER_LOGIN, USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    thrown(BlockingException)
  }

  void 'should not fail on null callback'() {
    when:
    tracker.onUserEvent(IDENTIFICATION, 'test-user', [:])

    then:
    noExceptionThrown()
    provider.getCallback(EVENTS.user()) >> null
  }

  void 'test onUserEvent (automated login events should not overwrite SDK)'() {
    when:
    tracker.onUserEvent(IDENTIFICATION, USER_ID, [:])

    then: 'SDK data remains untouched'
    1 * traceSegment.getTagTop('_dd.appsec.user.collection_mode') >> SDK.fullName()
    1 * traceSegment.setTagTop('_dd.appsec.usr.id', USER_ID)
    0 * _
  }


  void 'test onLoginSuccess (automated login events should not overwrite SDK)'() {
    when:
    tracker.onLoginSuccessEvent(IDENTIFICATION, USER_LOGIN, null, [:])

    then:
    1 * traceSegment.getTagTop('_dd.appsec.events.users.login.success.sdk') >> true
    1 * traceSegment.setTagTop('_dd.appsec.usr.login', USER_LOGIN)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.auto.mode', IDENTIFICATION.fullName(), true)
    0 * _
  }

  void 'test onLoginFailure (automated login events should not overwrite SDK)'() {
    when:
    tracker.onLoginFailureEvent(IDENTIFICATION, USER_LOGIN, null, [:])

    then:
    1 * traceSegment.getTagTop('_dd.appsec.events.users.login.failure.sdk') >> true
    1 * traceSegment.setTagTop('_dd.appsec.usr.login', USER_LOGIN)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', IDENTIFICATION.fullName(), true)
    0 * _
  }

  void 'test onUserNotFound (automated login events should not overwrite SDK)'() {
    when:
    tracker.onUserNotFound(IDENTIFICATION)

    then:
    1 * traceSegment.getTagTop('_dd.appsec.events.users.login.failure.sdk') >> true
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', IDENTIFICATION.fullName(), true)
    0 * _
  }

  private static void assertAppSecSdkEvent(final LoginEvent event, final LoginVersion version) {
    final metrics = WafMetricCollector.get().with {
      prepareMetrics()
      drain()
    }
    final expectedTags = ["event_type:${event.getTag()}".toString(), "sdk_version:${version.getTag()}".toString()]
    final metric = metrics.find { it.metricName == 'sdk.event'}
    assert metric != null
    assert metric.namespace == 'appsec'
    assert metric.type == 'count'
    assert metric.value == 1
    assert metric.tags.size() == 2
    assert metric.tags.containsAll(expectedTags)
  }

  private static class ActionFlow<T> implements Flow<T> {

    private Action action

    @Override
    Action getAction() {
      return action
    }

    @Override
    Object getResult() {
      return null
    }
  }
}
