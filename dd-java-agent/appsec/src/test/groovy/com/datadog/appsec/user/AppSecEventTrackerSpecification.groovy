package com.datadog.appsec.user

import com.datadog.appsec.gateway.NoopFlow
import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingException
import datadog.trace.api.EventTracker
import datadog.trace.api.GlobalTracer
import datadog.trace.api.UserIdCollectionMode
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.telemetry.WafMetricCollector
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

import static datadog.trace.api.UserIdCollectionMode.SDK
import static datadog.trace.api.gateway.Events.EVENTS

class AppSecEventTrackerSpecification extends DDSpecification {

  private static final String USER_ID = 'user'
  private static final String ANONYMIZED_USER_ID = 'anon_04f8996da763b7a969b1028ee3007569'

  @Shared
  private static boolean appSecActiveBefore = ActiveSubsystems.APPSEC_ACTIVE
  @Shared
  private static EventTracker eventTrackerBefore = GlobalTracer.getEventTracker()

  private AppSecEventTrackerImpl tracker
  private TraceSegment traceSegment
  private TracerAPI tracer
  private AgentSpan span
  private CallbackProvider provider
  private TriFunction<RequestContext, UserIdCollectionMode, String, Flow<Void>> userCallback
  private TriFunction<RequestContext, UserIdCollectionMode, String, Flow<Void>> loginSuccessCallback
  private TriFunction<RequestContext, UserIdCollectionMode, String, Flow<Void>> loginFailureCallback

  void setup() {
    traceSegment = Mock(TraceSegment)
    span = Stub(AgentSpan)
    userCallback = Mock(TriFunction)
    loginSuccessCallback = Mock(TriFunction)
    loginFailureCallback = Mock(TriFunction)
    provider = Stub(CallbackProvider) {
      getCallback(EVENTS.userId()) >> userCallback
      getCallback(EVENTS.loginSuccess()) >> loginSuccessCallback
      getCallback(EVENTS.loginFailure()) >> loginFailureCallback
    }
    tracer = Stub(TracerAPI) {
      getTraceSegment() >> traceSegment
      activeSpan() >> span
      getCallbackProvider(RequestContextSlot.APPSEC) >> provider
    }
    tracker = new AppSecEventTrackerImpl() {
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
    given:
    final sanitize = false // non custom event

    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent('user1', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true, sanitize)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.sdk', true, sanitize)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1': 'value1', 'key2': 'value2'], sanitize)
    1 * loginSuccessCallback.apply(_ as RequestContext, SDK, 'user1') >> NoopFlow.INSTANCE
    0 * _
  }

  def 'test track login failure event (SDK)'() {
    given:
    final sanitize = false // non custom event

    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent('user1', true, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, sanitize)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.sdk', true, sanitize)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.id', 'user1', sanitize)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.exists', true, sanitize)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1': 'value1', 'key2': 'value2'], sanitize)
    1 * loginFailureCallback.apply(_ as RequestContext, SDK, 'user1') >> NoopFlow.INSTANCE
    0 * _
  }

  def 'test track custom event (SDK)'() {
    given:
    final sanitize = true // custom event

    when:
    GlobalTracer.getEventTracker().trackCustomEvent('myevent', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.myevent.track', true, sanitize)
    1 * traceSegment.setTagTop('_dd.appsec.events.myevent.sdk', true, sanitize)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.setTagTop('appsec.events.myevent', ['key1': 'value1', 'key2': 'value2'], sanitize)
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
    setup:
    final sanitize = false // non custom event
    final collectionMode = UserIdCollectionMode.fromString(mode, null)

    when:
    tracker.onSignupEvent(collectionMode, USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.signup.auto.mode', modeTag, sanitize)
    1 * traceSegment.getTagTop('_dd.appsec.user.collection_mode') >> null
    1 * traceSegment.setTagTop('appsec.events.users.signup.track', true, sanitize)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.setTagTop('appsec.events.users.signup', ['key1': 'value1', 'key2': 'value2'], sanitize)
    1 * userCallback.apply(_ as RequestContext, collectionMode, expectedUserId) >> NoopFlow.INSTANCE
    0 * _

    where:
    mode    | modeTag          | expectedUserId
    'anon'  | 'anonymization'  | ANONYMIZED_USER_ID
    'ident' | 'identification' | USER_ID
  }

  def "test onLoginSuccess (#mode)"() {
    setup:
    final sanitize = false // non custom event
    final collectionMode = UserIdCollectionMode.fromString(mode, null)

    when:
    tracker.onLoginSuccessEvent(collectionMode, USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.auto.mode', modeTag, sanitize)
    1 * traceSegment.getTagTop('_dd.appsec.user.collection_mode') >> null
    1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true, sanitize)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1': 'value1', 'key2': 'value2'], sanitize)
    1 * loginSuccessCallback.apply(_ as RequestContext, collectionMode, expectedUserId) >> NoopFlow.INSTANCE
    0 * _

    where:
    mode    | modeTag          | expectedUserId
    'anon'  | 'anonymization'  | ANONYMIZED_USER_ID
    'ident' | 'identification' | USER_ID
  }

  def "test onLoginFailed #description (#mode)"() {
    setup:
    final sanitize = false // non custom event
    final collectionMode = UserIdCollectionMode.fromString(mode, null)

    when:
    tracker.onLoginFailureEvent(collectionMode, USER_ID, null, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.auto.mode', modeTag, sanitize)
    1 * traceSegment.getTagTop('_dd.appsec.user.collection_mode') >> null
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true, sanitize)
    1 * traceSegment.setTagTop('asm.keep', true)
    1 * traceSegment.setTagTop('_dd.p.appsec', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.id', expectedUserId, sanitize)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1': 'value1', 'key2': 'value2'], sanitize)
    1 * loginFailureCallback.apply(_ as RequestContext, collectionMode, expectedUserId) >> NoopFlow.INSTANCE
    0 * _

    where:
    mode    | modeTag          | description           | expectedUserId
    'anon'  | 'anonymization'  | 'with existing user'  | ANONYMIZED_USER_ID
    'anon'  | 'anonymization'  | 'user doesn\'t exist' | ANONYMIZED_USER_ID
    'ident' | 'identification' | 'with existing user'  | USER_ID
    'ident' | 'identification' | 'user doesn\'t exist' | USER_ID
  }

  def "test onUserEvent (#mode)"() {
    setup:
    final collectionMode = UserIdCollectionMode.fromString(mode, null)

    when:
    tracker.onUserEvent(collectionMode, USER_ID)

    then:
    1 * traceSegment.getTagTop('_dd.appsec.user.collection_mode') >> null
    1 * userCallback.apply(_ as RequestContext, collectionMode, expectedUserId) >> NoopFlow.INSTANCE
    0 * _

    where:
    mode    | modeTag          | expectedUserId
    'anon'  | 'anonymization'  | ANONYMIZED_USER_ID
    'ident' | 'identification' | USER_ID
  }


  def "test onUserNotFound (#mode)"() {
    setup:
    final collectionMode = UserIdCollectionMode.fromString(mode, null)

    when:
    tracker.onUserNotFound(collectionMode)

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

  void 'test missing user id callback'() {
    setup:
    final collector = WafMetricCollector.get()
    collector.prepareMetrics()
    collector.drain()
    final collectionMode = UserIdCollectionMode.fromString('ident', null)

    when:
    tracker.onLoginSuccessEvent(collectionMode, null, [:])

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
    final anonymized = AppSecEventTrackerImpl.anonymize(userId)

    then:
    anonymized == expected

    where:
    userId                  | expected
    null                    | null
    'zouzou@sansgluten.com' | 'anon_0c76692372ebf01a7da6e9570fb7d0a1'
  }

  void 'test that SDK produced events are not overridden by auto login events'() {
    setup:
    traceSegment.getTagTop('_dd.appsec.user.collection_mode') >> 'sdk'

    when:
    tracker.onLoginSuccessEvent(mode, USER_ID, ['key1': 'value1', 'key2': 'value2'])

    then:
    if (mode == SDK) {
      // the SDK can override itself
      1 * loginSuccessCallback.apply(_, SDK, USER_ID) >> NoopFlow.INSTANCE
    } else {
      // auto login events should not modify SDK produced ones
      0 * loginSuccessCallback.apply(_, _, _, _)
    }

    where:
    mode << [SDK, UserIdCollectionMode.IDENTIFICATION]
  }

  void 'test blocking on a userId'() {
    setup:
    final action = new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO)
    loginSuccessCallback.apply(_ as RequestContext, SDK, USER_ID) >> new ActionFlow<Void>(action: action)
    traceSegment.getTagTop('_dd.appsec.user.collection_mode') >> 'sdk'

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
