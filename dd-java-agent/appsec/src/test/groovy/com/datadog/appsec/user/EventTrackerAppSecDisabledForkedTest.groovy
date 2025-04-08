package com.datadog.appsec.user

import datadog.appsec.api.login.EventTrackerV2
import datadog.appsec.api.user.User
import datadog.trace.api.GlobalTracer
import datadog.trace.api.UserIdCollectionMode
import datadog.trace.api.appsec.AppSecEventTracker
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.ActiveSubsystems
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.UserIdCollectionMode.IDENTIFICATION

class EventTrackerAppSecDisabledForkedTest extends DDSpecification {

  TraceSegment traceSegment

  AppSecEventTracker tracker

  void setupSpec() {
    injectSysConfig('dd.appsec.enabled', 'false')
    ActiveSubsystems.APPSEC_ACTIVE = false
  }

  void setup() {
    tracker = new AppSecEventTracker()
    GlobalTracer.setEventTracker(tracker)
    EventTrackerV2.setEventTrackerService(tracker)
    User.setUserService(tracker)
    traceSegment = Mock(TraceSegment)
    final tracer = Stub(AgentTracer.TracerAPI) {
      getTraceSegment() >> traceSegment
      getCallbackProvider(RequestContextSlot.APPSEC) >> null
    }
    AgentTracer.forceRegister(tracer)
  }

  void 'test track login success event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent('user', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.sdk', true, true)
  }

  void 'test track login failure event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent('user', true, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.sdk', true, true)
  }

  void 'test track custom event (SDK)'() {
    when:
    GlobalTracer.getEventTracker().trackCustomEvent('myevent', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.myevent.sdk', true, true)
  }

  void 'test track login success event V2 (SDK)'() {
    when:
    EventTrackerV2.trackUserLoginSuccess('user', 'id', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.sdk', true, true)
  }

  void 'test track login failure event V2 (SDK)'() {
    when:
    EventTrackerV2.trackUserLoginFailure('user', true, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.sdk', true, true)
  }

  void 'test track custom event V2 (SDK)'() {
    when:
    EventTrackerV2.trackCustomEvent('myevent', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('_dd.appsec.events.myevent.sdk', true, true)
  }

  void 'test onSignup'() {
    when:
    tracker.onSignupEvent(IDENTIFICATION, 'user', ['key1': 'value1', 'key2': 'value2'])

    then:
    0 * _
  }

  void 'test onLoginSuccess'() {

    when:
    tracker.onLoginSuccessEvent(IDENTIFICATION, 'user', ['key1': 'value1', 'key2': 'value2'])

    then:
    0 * _
  }

  void 'test onLoginFailed'() {
    when:
    tracker.onLoginFailureEvent(IDENTIFICATION, 'user', true, ['key1': 'value1', 'key2': 'value2'])

    then:
    0 * _

    where:
    mode << UserIdCollectionMode.values()
  }

  def 'test onUserEvent'() {
    when:
    tracker.onUserEvent(IDENTIFICATION, 'user')

    then:
    0 * _
  }

  def 'test onUserNotFound'() {
    when:
    tracker.onUserNotFound(IDENTIFICATION)

    then:
    0 * _
  }
}

