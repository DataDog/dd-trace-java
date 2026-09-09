package com.datadog.appsec.user;

import static datadog.appsec.api.user.User.setUser;
import static datadog.trace.api.ProductTraceSource.ASM;
import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION;
import static datadog.trace.api.UserIdCollectionMode.DISABLED;
import static datadog.trace.api.UserIdCollectionMode.IDENTIFICATION;
import static datadog.trace.api.UserIdCollectionMode.SDK;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.telemetry.LoginEvent.CUSTOM;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS;
import static datadog.trace.api.telemetry.LoginEvent.SIGN_UP;
import static datadog.trace.api.telemetry.LoginVersion.V1;
import static datadog.trace.api.telemetry.LoginVersion.V2;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.datadog.appsec.gateway.NoopFlow;
import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.appsec.api.login.EventTrackerService;
import datadog.appsec.api.login.EventTrackerV2;
import datadog.appsec.api.user.User;
import datadog.appsec.api.user.UserService;
import datadog.trace.api.EventTracker;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.UserIdCollectionMode;
import datadog.trace.api.appsec.AppSecEventTracker;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.api.telemetry.LoginEvent;
import datadog.trace.api.telemetry.LoginVersion;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.api.telemetry.WafMetricCollector.WafMetric;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer.TracerAPI;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

@SuppressWarnings("deprecation") // exercises the deprecated v1 EventTracker API on purpose
class AppSecEventTrackerTest extends DDJavaSpecification {

  private static final String USER_LOGIN = "user";
  private static final String ANONYMIZED_USER_LOGIN = "anon_04f8996da763b7a969b1028ee3007569";
  private static final String USER_ID = "1";
  private static final String ANONYMIZED_USER_ID = "anon_6b86b273ff34fce19d6b804eff5a3f57";
  private static final Map<String, String> METADATA = metadata();

  private static boolean appSecActiveBefore;
  private static EventTracker eventTrackerBefore;

  private TestAppSecEventTracker tracker;
  private TraceSegment traceSegment;
  private CallbackProvider provider;
  private BiFunction<RequestContext, String, Flow<Void>> user;
  private TriFunction<RequestContext, LoginEvent, String, Flow<Void>> loginEvent;

  @BeforeAll
  static void saveGlobalState() {
    appSecActiveBefore = ActiveSubsystems.APPSEC_ACTIVE;
    eventTrackerBefore = GlobalTracer.getEventTracker();
  }

  /**
   * {@link User} and {@link EventTrackerV2} hold their implementation in a static field with no
   * getter, so the pre-test value cannot be captured; resetting them to their no-op defaults at
   * least keeps this test's tracker (and its mocks) from leaking into later tests.
   */
  @AfterAll
  static void restoreGlobalState() {
    ActiveSubsystems.APPSEC_ACTIVE = appSecActiveBefore;
    GlobalTracer.setEventTracker(eventTrackerBefore);
    User.setUserService(UserService.NO_OP);
    EventTrackerV2.setEventTrackerService(EventTrackerService.NO_OP);
  }

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setup() {
    traceSegment = mock(TraceSegment.class);
    AgentSpan span = mock(AgentSpan.class);
    when(span.getRequestContext()).thenReturn(mock(RequestContext.class));
    user = mock(BiFunction.class);
    loginEvent = mock(TriFunction.class);
    when(user.apply(any(), any())).thenReturn(NoopFlow.INSTANCE);
    when(loginEvent.apply(any(), any(), any())).thenReturn(NoopFlow.INSTANCE);

    provider = mock(CallbackProvider.class);
    when(provider.getCallback(EVENTS.user())).thenReturn(user);
    when(provider.getCallback(EVENTS.loginEvent())).thenReturn(loginEvent);

    TracerAPI tracer = mock(TracerAPI.class);
    when(tracer.getTraceSegment()).thenReturn(traceSegment);
    when(tracer.activeSpan()).thenReturn(span);
    when(tracer.getCallbackProvider(RequestContextSlot.APPSEC)).thenReturn(provider);

    tracker = new TestAppSecEventTracker(tracer);
    GlobalTracer.setEventTracker(tracker);
    User.setUserService(tracker);
    EventTrackerV2.setEventTrackerService(tracker.v2EventTrackerService());
    ActiveSubsystems.APPSEC_ACTIVE = true;

    // discard telemetry produced elsewhere so each assertion only sees its own event
    drainSdkEvents();
  }

  @Test
  void trackLoginSuccessEvent() {
    GlobalTracer.getEventTracker().trackLoginSuccessEvent(USER_ID, METADATA);

    verify(traceSegment).setTagTop("usr.id", USER_ID);
    verify(traceSegment).setTagTop("appsec.events.users.login.success.usr.login", USER_ID, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.success", METADATA, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.success.track", true, true);
    verify(traceSegment).setTagTop("_dd.appsec.events.users.login.success.sdk", true, true);
    verify(traceSegment).setTagTop("asm.keep", true);
    verify(traceSegment).setTagTop("_dd.p.ts", ASM);
    verify(loginEvent).apply(isA(RequestContext.class), eq(LOGIN_SUCCESS), eq(USER_ID));
    verify(user).apply(isA(RequestContext.class), eq(USER_ID));
    verifyNoMoreInteractions(traceSegment, user, loginEvent);

    assertAppSecSdkEvent(LOGIN_SUCCESS, V1);
  }

  @Test
  void trackLoginFailureEvent() {
    GlobalTracer.getEventTracker().trackLoginFailureEvent(USER_ID, true, METADATA);

    verify(traceSegment).setTagTop("appsec.events.users.login.failure.usr.id", USER_ID, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.failure.usr.login", USER_ID, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.failure.usr.exists", true, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.failure", METADATA, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.failure.track", true, true);
    verify(traceSegment).setTagTop("_dd.appsec.events.users.login.failure.sdk", true, true);
    verify(traceSegment).setTagTop("asm.keep", true);
    verify(traceSegment).setTagTop("_dd.p.ts", ASM);
    verify(loginEvent).apply(isA(RequestContext.class), eq(LOGIN_FAILURE), eq(USER_ID));
    verify(user).apply(isA(RequestContext.class), eq(USER_ID));
    verifyNoMoreInteractions(traceSegment, user, loginEvent);

    assertAppSecSdkEvent(LOGIN_FAILURE, V1);
  }

  @Test
  void trackCustomEvent() {
    GlobalTracer.getEventTracker().trackCustomEvent("myevent", METADATA);

    verify(traceSegment).setTagTop("appsec.events.myevent", METADATA, true);
    verify(traceSegment).setTagTop("appsec.events.myevent.track", true, true);
    verify(traceSegment).setTagTop("_dd.appsec.events.myevent.sdk", true, true);
    verify(traceSegment).setTagTop("asm.keep", true);
    verify(traceSegment).setTagTop("_dd.p.ts", ASM);
    verifyNoMoreInteractions(traceSegment, user, loginEvent);

    assertAppSecSdkEvent(CUSTOM, V1);
  }

  @Test
  void trackLoginSuccessEventV2() {
    EventTrackerV2.trackUserLoginSuccess(USER_LOGIN, USER_ID, METADATA);

    verify(traceSegment).setTagTop("usr.id", USER_ID);
    verify(traceSegment).setTagTop("usr", METADATA);
    verify(traceSegment).setTagTop("appsec.events.users.login.success.usr.id", USER_ID, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.success.usr", METADATA, true);
    verify(traceSegment).setTagTop("_dd.appsec.user.collection_mode", "sdk");
    verify(traceSegment).setTagTop("appsec.events.users.login.success.usr.login", USER_LOGIN, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.success", METADATA, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.success.track", true, true);
    verify(traceSegment).setTagTop("_dd.appsec.events.users.login.success.sdk", true, true);
    verify(traceSegment, times(2)).setTagTop("asm.keep", true);
    verify(traceSegment, times(2)).setTagTop("_dd.p.ts", ASM);
    verify(loginEvent).apply(isA(RequestContext.class), eq(LOGIN_SUCCESS), eq(USER_LOGIN));
    verify(user).apply(isA(RequestContext.class), eq(USER_ID));
    verifyNoMoreInteractions(traceSegment, user, loginEvent);

    assertAppSecSdkEvent(LOGIN_SUCCESS, V2);
  }

  @Test
  void trackLoginFailureEventV2() {
    EventTrackerV2.trackUserLoginFailure(USER_LOGIN, true, METADATA);

    verify(traceSegment).setTagTop("appsec.events.users.login.failure.usr.login", USER_LOGIN, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.failure.usr.exists", true, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.failure", METADATA, true);
    verify(traceSegment).setTagTop("appsec.events.users.login.failure.track", true, true);
    verify(traceSegment).setTagTop("_dd.appsec.events.users.login.failure.sdk", true, true);
    verify(traceSegment).setTagTop("asm.keep", true);
    verify(traceSegment).setTagTop("_dd.p.ts", ASM);
    verify(loginEvent).apply(isA(RequestContext.class), eq(LOGIN_FAILURE), eq(USER_LOGIN));
    verifyNoMoreInteractions(traceSegment, user, loginEvent);

    assertAppSecSdkEvent(LOGIN_FAILURE, V2);
  }

  @Test
  void trackCustomEventV2() {
    EventTrackerV2.trackCustomEvent("myevent", METADATA);

    verify(traceSegment).setTagTop("appsec.events.myevent", METADATA, true);
    verify(traceSegment).setTagTop("appsec.events.myevent.track", true, true);
    verify(traceSegment).setTagTop("_dd.appsec.events.myevent.sdk", true, true);
    verify(traceSegment).setTagTop("asm.keep", true);
    verify(traceSegment).setTagTop("_dd.p.ts", ASM);
    verifyNoMoreInteractions(traceSegment, user, loginEvent);

    assertAppSecSdkEvent(CUSTOM, V2);
  }

  @Test
  void trackUser() {
    setUser(USER_ID, METADATA);

    verify(traceSegment).setTagTop("usr.id", USER_ID);
    verify(traceSegment).setTagTop("usr", METADATA);
    verify(traceSegment).setTagTop("_dd.appsec.user.collection_mode", SDK.fullName());
    verify(traceSegment).setTagTop("asm.keep", true);
    verify(traceSegment).setTagTop("_dd.p.ts", ASM);
    verify(user).apply(isA(RequestContext.class), eq(USER_ID));
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  @Test
  void wrongEventArgumentValidation() {
    EventTracker eventTracker = GlobalTracer.getEventTracker();

    assertThrows(
        IllegalArgumentException.class, () -> eventTracker.trackLoginSuccessEvent(null, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> eventTracker.trackLoginFailureEvent(null, false, null));
    assertThrows(IllegalArgumentException.class, () -> eventTracker.trackCustomEvent(null, null));
    assertThrows(
        IllegalArgumentException.class, () -> eventTracker.trackLoginSuccessEvent("", null));
    assertThrows(
        IllegalArgumentException.class, () -> eventTracker.trackLoginFailureEvent("", false, null));
    assertThrows(IllegalArgumentException.class, () -> eventTracker.trackCustomEvent("", null));
    assertThrows(IllegalArgumentException.class, () -> setUser(null, null));
  }

  @TableTest({
    "scenario       | mode          ",
    "identification | IDENTIFICATION",
    "anonymization  | ANONYMIZATION ",
    "disabled       | DISABLED      "
  })
  void onSignup(UserIdCollectionMode mode) {
    String expectedUserLogin = mode == ANONYMIZATION ? ANONYMIZED_USER_LOGIN : USER_LOGIN;

    tracker.onSignupEvent(mode, USER_LOGIN, METADATA);

    if (mode != DISABLED) {
      verify(traceSegment).getTagTop("_dd.appsec.events.users.signup.sdk"); // no SDK event before
      verify(traceSegment).setTagTop("_dd.appsec.usr.login", expectedUserLogin);
      verify(traceSegment)
          .setTagTop("_dd.appsec.events.users.signup.auto.mode", mode.fullName(), true);
      verify(traceSegment)
          .setTagTop("appsec.events.users.signup.usr.login", expectedUserLogin, true);
      verify(traceSegment).setTagTop("appsec.events.users.signup", METADATA, true);
      verify(traceSegment).setTagTop("appsec.events.users.signup.track", true, true);
      verify(traceSegment).setTagTop("asm.keep", true);
      verify(traceSegment).setTagTop("_dd.p.ts", ASM);
      verify(loginEvent).apply(isA(RequestContext.class), eq(SIGN_UP), eq(expectedUserLogin));
    }
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  @TableTest({
    "scenario       | mode          ",
    "identification | IDENTIFICATION",
    "anonymization  | ANONYMIZATION ",
    "disabled       | DISABLED      "
  })
  void onLoginSuccess(UserIdCollectionMode mode) {
    String expectedUserLogin = mode == ANONYMIZATION ? ANONYMIZED_USER_LOGIN : USER_LOGIN;

    tracker.onLoginSuccessEvent(mode, USER_LOGIN, METADATA);

    if (mode != DISABLED) {
      // no SDK event before
      verify(traceSegment).getTagTop("_dd.appsec.events.users.login.success.sdk");
      verify(traceSegment).setTagTop("_dd.appsec.usr.login", expectedUserLogin);
      verify(traceSegment)
          .setTagTop("_dd.appsec.events.users.login.success.auto.mode", mode.fullName(), true);
      verify(traceSegment)
          .setTagTop("appsec.events.users.login.success.usr.login", expectedUserLogin, true);
      verify(traceSegment).setTagTop("appsec.events.users.login.success", METADATA, true);
      verify(traceSegment).setTagTop("appsec.events.users.login.success.track", true, true);
      verify(traceSegment).setTagTop("asm.keep", true);
      verify(traceSegment).setTagTop("_dd.p.ts", ASM);
      verify(loginEvent).apply(isA(RequestContext.class), eq(LOGIN_SUCCESS), eq(expectedUserLogin));
    }
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  @TableTest({
    "scenario       | mode          ",
    "identification | IDENTIFICATION",
    "anonymization  | ANONYMIZATION ",
    "disabled       | DISABLED      "
  })
  void onLoginFailed(UserIdCollectionMode mode) {
    String expectedUserLogin = mode == ANONYMIZATION ? ANONYMIZED_USER_LOGIN : USER_LOGIN;

    tracker.onLoginFailureEvent(mode, USER_LOGIN, true, METADATA);

    if (mode != DISABLED) {
      // no SDK event before
      verify(traceSegment).getTagTop("_dd.appsec.events.users.login.failure.sdk");
      verify(traceSegment).setTagTop("_dd.appsec.usr.login", expectedUserLogin);
      verify(traceSegment)
          .setTagTop("_dd.appsec.events.users.login.failure.auto.mode", mode.fullName(), true);
      verify(traceSegment)
          .setTagTop("appsec.events.users.login.failure.usr.login", expectedUserLogin, true);
      verify(traceSegment).setTagTop("appsec.events.users.login.failure", METADATA, true);
      verify(traceSegment).setTagTop("appsec.events.users.login.failure.usr.exists", true, true);
      verify(traceSegment).setTagTop("appsec.events.users.login.failure.track", true, true);
      verify(traceSegment).setTagTop("asm.keep", true);
      verify(traceSegment).setTagTop("_dd.p.ts", ASM);
      verify(loginEvent).apply(isA(RequestContext.class), eq(LOGIN_FAILURE), eq(expectedUserLogin));
    }
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  @TableTest({
    "scenario       | mode          ",
    "identification | IDENTIFICATION",
    "anonymization  | ANONYMIZATION ",
    "disabled       | DISABLED      "
  })
  void onUserEvent(UserIdCollectionMode mode) {
    String expectedUserId = mode == ANONYMIZATION ? ANONYMIZED_USER_ID : USER_ID;

    tracker.onUserEvent(mode, USER_ID, emptyMap());

    if (mode != DISABLED) {
      verify(traceSegment).setTagTop("_dd.appsec.usr.id", expectedUserId);
      verify(traceSegment).getTagTop("_dd.appsec.user.collection_mode"); // no user event before
      verify(traceSegment).setTagTop("_dd.appsec.user.collection_mode", mode.fullName());
      verify(traceSegment).setTagTop("usr.id", expectedUserId);
      verify(traceSegment).setTagTop("asm.keep", true);
      verify(traceSegment).setTagTop("_dd.p.ts", ASM);
      verify(user).apply(isA(RequestContext.class), eq(expectedUserId));
    }
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  @TableTest({
    "scenario       | mode          ",
    "identification | IDENTIFICATION",
    "anonymization  | ANONYMIZATION ",
    "disabled       | DISABLED      "
  })
  void onUserNotFound(UserIdCollectionMode mode) {
    tracker.onUserNotFound(mode);

    if (mode != DISABLED) {
      // no SDK event before
      verify(traceSegment).getTagTop("_dd.appsec.events.users.login.failure.sdk");
      verify(traceSegment)
          .setTagTop("_dd.appsec.events.users.login.failure.auto.mode", mode.fullName(), true);
      verify(traceSegment).setTagTop("appsec.events.users.login.failure.usr.exists", false, true);
      verify(traceSegment).setTagTop("appsec.events.users.login.failure.track", true, true);
      verify(traceSegment).setTagTop("asm.keep", true);
      verify(traceSegment).setTagTop("_dd.p.ts", ASM);
    }
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  // spotless:off
  @TableTest({
    "scenario            | appsec | collectionMode | trackingMode | result",
    // disabled states
    "off/none/none       | false  |                |              | false ",
    "off/none/safe       | false  |                | safe         | false ",
    "off/none/extended   | false  |                | extended     | false ",
    "off/none/disabled   | false  |                | disabled     | false ",
    "off/ident/none      | false  | ident          |              | false ",
    "off/ident/safe      | false  | ident          | safe         | false ",
    "off/ident/extended  | false  | ident          | extended     | false ",
    "off/ident/disabled  | false  | ident          | disabled     | false ",
    "off/anon/none       | false  | anon           |              | false ",
    "off/anon/safe       | false  | anon           | safe         | false ",
    "off/anon/extended   | false  | anon           | extended     | false ",
    "off/anon/disabled   | false  | anon           | disabled     | false ",
    "off/disabled/none   | false  | disabled       |              | false ",
    "off/disabled/safe   | false  | disabled       | safe         | false ",
    "off/disabled/ext    | false  | disabled       | extended     | false ",
    "off/disabled/dis    | false  | disabled       | disabled     | false ",
    "on/none/disabled    | true   |                | disabled     | false ",
    "on/disabled/none    | true   | disabled       |              | false ",
    "on/disabled/safe    | true   | disabled       | safe         | false ",
    "on/disabled/extended| true   | disabled       | extended     | false ",
    "on/disabled/disabled| true   | disabled       | disabled     | false ",
    // enabled states
    "on/none/none        | true   |                |              | true  ",
    "on/none/safe        | true   |                | safe         | true  ",
    "on/none/extended    | true   |                | extended     | true  ",
    "on/ident/none       | true   | ident          |              | true  ",
    "on/ident/safe       | true   | ident          | safe         | true  ",
    "on/ident/extended   | true   | ident          | extended     | true  ",
    "on/ident/disabled   | true   | ident          | disabled     | true  ",
    "on/anon/none        | true   | anon           |              | true  ",
    "on/anon/safe        | true   | anon           | safe         | true  ",
    "on/anon/extended    | true   | anon           | extended     | true  ",
    "on/anon/disabled    | true   | anon           | disabled     | true  "
  })
  // spotless:on
  void isEnabled(boolean appsec, String collectionMode, String trackingMode, boolean result) {
    ActiveSubsystems.APPSEC_ACTIVE = appsec;
    UserIdCollectionMode mode = UserIdCollectionMode.fromString(collectionMode, trackingMode);

    assertEquals(result, tracker.isEnabled(mode));
  }

  @Test
  void blockingOnALogin() {
    Flow.Action.RequestBlockingAction action =
        new Flow.Action.RequestBlockingAction(403, BlockingContentType.AUTO);
    when(loginEvent.apply(isA(RequestContext.class), eq(LOGIN_SUCCESS), eq(USER_LOGIN)))
        .thenReturn(new ActionFlow<>(action));

    assertThrows(
        BlockingException.class,
        () -> tracker.onLoginSuccessEvent(SDK, USER_LOGIN, USER_ID, METADATA));
  }

  @Test
  void shouldNotFailOnNullCallback() {
    when(provider.getCallback(EVENTS.user())).thenReturn(null);

    assertDoesNotThrow(() -> tracker.onUserEvent(IDENTIFICATION, "test-user", emptyMap()));
  }

  @Test
  void onUserEventDoesNotOverwriteSdk() {
    when(traceSegment.getTagTop("_dd.appsec.user.collection_mode")).thenReturn(SDK.fullName());

    tracker.onUserEvent(IDENTIFICATION, USER_ID, emptyMap());

    // SDK data remains untouched
    verify(traceSegment).getTagTop("_dd.appsec.user.collection_mode");
    verify(traceSegment).setTagTop("_dd.appsec.usr.id", USER_ID);
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  @Test
  void onLoginSuccessDoesNotOverwriteSdk() {
    when(traceSegment.getTagTop("_dd.appsec.events.users.login.success.sdk")).thenReturn(true);

    tracker.onLoginSuccessEvent(IDENTIFICATION, USER_LOGIN, null, emptyMap());

    verify(traceSegment).getTagTop("_dd.appsec.events.users.login.success.sdk");
    verify(traceSegment).setTagTop("_dd.appsec.usr.login", USER_LOGIN);
    verify(traceSegment)
        .setTagTop(
            "_dd.appsec.events.users.login.success.auto.mode", IDENTIFICATION.fullName(), true);
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  @Test
  void onLoginFailureDoesNotOverwriteSdk() {
    when(traceSegment.getTagTop("_dd.appsec.events.users.login.failure.sdk")).thenReturn(true);

    tracker.onLoginFailureEvent(IDENTIFICATION, USER_LOGIN, null, emptyMap());

    verify(traceSegment).getTagTop("_dd.appsec.events.users.login.failure.sdk");
    verify(traceSegment).setTagTop("_dd.appsec.usr.login", USER_LOGIN);
    verify(traceSegment)
        .setTagTop(
            "_dd.appsec.events.users.login.failure.auto.mode", IDENTIFICATION.fullName(), true);
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  @Test
  void onUserNotFoundDoesNotOverwriteSdk() {
    when(traceSegment.getTagTop("_dd.appsec.events.users.login.failure.sdk")).thenReturn(true);

    tracker.onUserNotFound(IDENTIFICATION);

    verify(traceSegment).getTagTop("_dd.appsec.events.users.login.failure.sdk");
    verify(traceSegment)
        .setTagTop(
            "_dd.appsec.events.users.login.failure.auto.mode", IDENTIFICATION.fullName(), true);
    verifyNoMoreInteractions(traceSegment, user, loginEvent);
  }

  private static Map<String, String> metadata() {
    Map<String, String> metadata = new HashMap<>();
    metadata.put("key1", "value1");
    metadata.put("key2", "value2");
    return metadata;
  }

  private static void assertAppSecSdkEvent(LoginEvent event, LoginVersion version) {
    List<WafMetric> sdkEvents = drainSdkEvents();
    assertEquals(1, sdkEvents.size());
    WafMetric metric = sdkEvents.get(0);
    assertEquals("appsec", metric.namespace);
    assertEquals("count", metric.type);
    assertEquals(1L, metric.value.longValue());
    assertEquals(
        asList("event_type:" + event.getTag(), "sdk_version:" + version.getTag()), metric.tags);
  }

  private static List<WafMetric> drainSdkEvents() {
    WafMetricCollector collector = WafMetricCollector.get();
    collector.prepareMetrics();
    List<WafMetric> sdkEvents = new ArrayList<>();
    for (WafMetric metric : collector.drain()) {
      if ("sdk.event".equals(metric.metricName)) {
        sdkEvents.add(metric);
      }
    }
    return sdkEvents;
  }

  /**
   * Exposes the tracer used by the tracker and widens {@code isEnabled} so the test package can
   * call it; both are {@code protected} on {@link AppSecEventTracker}.
   */
  private static class TestAppSecEventTracker extends AppSecEventTracker {

    private final TracerAPI tracer;

    TestAppSecEventTracker(TracerAPI tracer) {
      this.tracer = tracer;
    }

    @Override
    protected TracerAPI tracer() {
      return tracer;
    }

    @Override
    public boolean isEnabled(UserIdCollectionMode mode) {
      return super.isEnabled(mode);
    }
  }

  private static class ActionFlow<T> implements Flow<T> {

    private final Action action;

    ActionFlow(Action action) {
      this.action = action;
    }

    @Override
    public Action getAction() {
      return action;
    }

    @Override
    public T getResult() {
      return null;
    }
  }
}
