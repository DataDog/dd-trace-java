package com.datadog.appsec.user

import com.datadog.appsec.gateway.NoopFlow
import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.api.EventTracker
import datadog.trace.api.GlobalTracer
import datadog.trace.api.UserIdCollectionMode
import datadog.trace.api.appsec.AppSecEventTracker
import datadog.trace.api.appsec.LoginEventCallback
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.UserIdCollectionMode.DISABLED
import static datadog.trace.api.UserIdCollectionMode.SDK
import static datadog.trace.api.gateway.Events.EVENTS

class AppSecEventTrackerSpecification extends DDSpecification {

  private static final String USER_ID = 'user'

  @Shared
  private static boolean appSecActiveBefore = ActiveSubsystems.APPSEC_ACTIVE
  @Shared
  private static EventTracker eventTrackerBefore = GlobalTracer.getEventTracker()

  private AppSecEventTracker tracker
  private TraceSegment traceSegment
  private TracerAPI tracer
  private AgentSpan span
  private CallbackProvider provider
  private TriFunction<RequestContext, UserIdCollectionMode, String, Flow<Void>> user
  private LoginEventCallback loginEvent

  void setup() {
    traceSegment = Mock(TraceSegment)
    span = Stub(AgentSpan)
    user = Mock(TriFunction)
    loginEvent = Mock(LoginEventCallback)

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
    ActiveSubsystems.APPSEC_ACTIVE = true
  }

  void cleanupSpec() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActiveBefore
    GlobalTracer.setEventTracker(eventTrackerBefore)
  }

  def 'test track login success event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent('user1', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * loginEvent.apply(_ as RequestContext, SDK, 'users.login.success', null, 'user1', ['key1': 'value1', 'key2': 'value2']) >> NoopFlow.INSTANCE
    0 * _
  }

  def 'test track login failure event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent('user1', true, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * loginEvent.apply(_ as RequestContext, SDK, 'users.login.failure', true, 'user1', ['key1': 'value1', 'key2': 'value2']) >> NoopFlow.INSTANCE
    0 * _
  }

  def 'test track custom event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackCustomEvent('myevent', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * loginEvent.apply(_ as RequestContext, SDK, 'myevent', null, null, ['key1': 'value1', 'key2': 'value2']) >> NoopFlow.INSTANCE
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
  }

  def "test onSignup (#mode)"() {
    when:
    tracker.onSignupEvent(mode, USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    if (mode != DISABLED) {
      1 * loginEvent.apply(_ as RequestContext, mode, 'users.signup', null, USER_ID, ['key1': 'value1', 'key2': 'value2']) >> NoopFlow.INSTANCE
    }
    0 * _

    where:
    mode << UserIdCollectionMode.values()
  }

  def "test onLoginSuccess (#mode)"() {
    when:
    tracker.onLoginSuccessEvent(mode, USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    if (mode != DISABLED) {
      1 * loginEvent.apply(_ as RequestContext, mode, 'users.login.success', null, USER_ID, ['key1': 'value1', 'key2': 'value2']) >> NoopFlow.INSTANCE
    }
    0 * _

    where:
    mode << UserIdCollectionMode.values()
  }

  def "test onLoginFailed (#mode)"() {
    when:
    tracker.onLoginFailureEvent(mode, USER_ID, null, ['key1': 'value1', 'key2': 'value2'])

    then:
    if (mode != DISABLED) {
      1 * loginEvent.apply(_ as RequestContext, mode, 'users.login.failure', null, USER_ID, ['key1': 'value1', 'key2': 'value2']) >> NoopFlow.INSTANCE
    }
    0 * _

    where:
    mode << UserIdCollectionMode.values()
  }

  def "test onUserEvent (#mode)"() {
    when:
    tracker.onUserEvent(mode, USER_ID)

    then:
    if (mode != DISABLED) {
      1 * user.apply(_ as RequestContext, mode, USER_ID) >> NoopFlow.INSTANCE
    }
    0 * _

    where:
    mode << UserIdCollectionMode.values()
  }

  def "test onUserNotFound (#mode)"() {
    when:
    tracker.onUserNotFound(mode)

    then:
    if (mode != DISABLED) {
      1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.exists', false)
    }
    0 * _

    where:
    mode << UserIdCollectionMode.values()
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

  void 'test blocking on a userId'() {
    setup:
    final action = new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO)
    loginEvent.apply(_ as RequestContext, SDK, 'users.login.success', null, USER_ID, ['key1': 'value1', 'key2': 'value2']) >> new ActionFlow<Void>(action: action)

    when:
    tracker.onLoginSuccessEvent(SDK, USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    thrown(BlockingException)
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
