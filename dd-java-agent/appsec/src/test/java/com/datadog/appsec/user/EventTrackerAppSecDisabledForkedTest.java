package com.datadog.appsec.user;

import static datadog.trace.api.UserIdCollectionMode.IDENTIFICATION;
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import datadog.appsec.api.login.EventTrackerV2;
import datadog.appsec.api.user.User;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.appsec.AppSecEventTracker;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation") // exercises the deprecated v1 EventTracker API on purpose
@WithConfig(key = APPSEC_ENABLED, value = "false")
class EventTrackerAppSecDisabledForkedTest extends DDJavaSpecification {

  private static final Map<String, String> METADATA = metadata();

  private TraceSegment traceSegment;
  private AppSecEventTracker tracker;

  @BeforeAll
  static void disableAppSec() {
    ActiveSubsystems.APPSEC_ACTIVE = false;
  }

  @BeforeEach
  void setup() {
    tracker = new AppSecEventTracker();
    GlobalTracer.setEventTracker(tracker);
    EventTrackerV2.setEventTrackerService(tracker.v2EventTrackerService());
    User.setUserService(tracker);
    traceSegment = mock(TraceSegment.class);
    TracerAPI tracer = mock(TracerAPI.class);
    when(tracer.getTraceSegment()).thenReturn(traceSegment);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(null);
    AgentTracer.forceRegister(tracer);
  }

  @Test
  void trackLoginSuccessEvent() {
    GlobalTracer.getEventTracker().trackLoginSuccessEvent("user", METADATA);

    verify(traceSegment).setTagTop("_dd.appsec.events.users.login.success.sdk", true, true);
  }

  @Test
  void trackLoginFailureEvent() {
    GlobalTracer.getEventTracker().trackLoginFailureEvent("user", true, METADATA);

    verify(traceSegment).setTagTop("_dd.appsec.events.users.login.failure.sdk", true, true);
  }

  @Test
  void trackCustomEvent() {
    GlobalTracer.getEventTracker().trackCustomEvent("myevent", METADATA);

    verify(traceSegment).setTagTop("_dd.appsec.events.myevent.sdk", true, true);
  }

  @Test
  void trackLoginSuccessEventV2() {
    EventTrackerV2.trackUserLoginSuccess("user", "id", METADATA);

    verify(traceSegment).setTagTop("_dd.appsec.events.users.login.success.sdk", true, true);
  }

  @Test
  void trackLoginFailureEventV2() {
    EventTrackerV2.trackUserLoginFailure("user", true, METADATA);

    verify(traceSegment).setTagTop("_dd.appsec.events.users.login.failure.sdk", true, true);
  }

  @Test
  void trackCustomEventV2() {
    EventTrackerV2.trackCustomEvent("myevent", METADATA);

    verify(traceSegment).setTagTop("_dd.appsec.events.myevent.sdk", true, true);
  }

  @Test
  void onSignup() {
    tracker.onSignupEvent(IDENTIFICATION, "user", METADATA);

    verifyNoInteractions(traceSegment);
  }

  @Test
  void onLoginSuccess() {
    tracker.onLoginSuccessEvent(IDENTIFICATION, "user", METADATA);

    verifyNoInteractions(traceSegment);
  }

  @Test
  void onLoginFailed() {
    tracker.onLoginFailureEvent(IDENTIFICATION, "user", true, METADATA);

    verifyNoInteractions(traceSegment);
  }

  @Test
  void onUserEvent() {
    tracker.onUserEvent(IDENTIFICATION, "user");

    verifyNoInteractions(traceSegment);
  }

  @Test
  void onUserNotFound() {
    tracker.onUserNotFound(IDENTIFICATION);

    verifyNoInteractions(traceSegment);
  }

  private static Map<String, String> metadata() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("key1", "value1");
    metadata.put("key2", "value2");
    return metadata;
  }
}
