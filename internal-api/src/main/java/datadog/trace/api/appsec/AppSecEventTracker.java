package datadog.trace.api.appsec;

import static datadog.trace.api.UserIdCollectionMode.DISABLED;
import static datadog.trace.api.UserIdCollectionMode.SDK;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS;
import static datadog.trace.api.telemetry.LoginEvent.SIGN_UP;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.EventTracker;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.UserIdCollectionMode;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.EventType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.function.BiFunction;

public class AppSecEventTracker extends EventTracker {

  private static AppSecEventTracker INSTANCE;

  public static AppSecEventTracker getEventTracker() {
    return INSTANCE;
  }

  public static void setEventTracker(final AppSecEventTracker tracker) {
    INSTANCE = tracker;
    GlobalTracer.setEventTracker(tracker == null ? EventTracker.NO_EVENT_TRACKER : tracker);
  }

  @Override
  public final void trackLoginSuccessEvent(String userId, Map<String, String> metadata) {
    if (userId == null || userId.isEmpty()) {
      throw new IllegalArgumentException("UserId is null or empty");
    }
    onLoginSuccessEvent(SDK, userId, metadata);
  }

  @Override
  public final void trackLoginFailureEvent(
      String userId, boolean exists, Map<String, String> metadata) {
    if (userId == null || userId.isEmpty()) {
      throw new IllegalArgumentException("UserId is null or empty");
    }
    onLoginFailureEvent(SDK, userId, exists, metadata);
  }

  @Override
  public final void trackCustomEvent(String eventName, Map<String, String> metadata) {
    if (eventName == null || eventName.isEmpty()) {
      throw new IllegalArgumentException("EventName is null or empty");
    }
    onCustomEvent(SDK, eventName, metadata);
  }

  public void onUserNotFound(final UserIdCollectionMode mode) {
    if (!isEnabled(mode)) {
      return;
    }
    final AgentTracer.TracerAPI tracer = tracer();
    if (tracer == null) {
      return;
    }
    final TraceSegment segment = tracer.getTraceSegment();
    if (segment == null) {
      return;
    }
    segment.setTagTop("appsec.events.users.login.failure.usr.exists", false);
  }

  public void onUserEvent(final UserIdCollectionMode mode, final String userId) {
    if (!isEnabled(mode)) {
      return;
    }
    dispatch(EVENTS.user(), (ctx, cb) -> cb.apply(ctx, mode, userId));
  }

  public void onSignupEvent(
      final UserIdCollectionMode mode, final String userId, final Map<String, String> metadata) {
    if (!isEnabled(mode)) {
      return;
    }
    dispatch(
        EVENTS.loginEvent(),
        (ctx, callback) -> callback.apply(ctx, mode, SIGN_UP.getSpanTag(), null, userId, metadata));
  }

  public void onLoginSuccessEvent(
      final UserIdCollectionMode mode, final String userId, final Map<String, String> metadata) {
    if (!isEnabled(mode)) {
      return;
    }
    dispatch(
        EVENTS.loginEvent(),
        (ctx, cb) -> cb.apply(ctx, mode, LOGIN_SUCCESS.getSpanTag(), null, userId, metadata));
  }

  public void onLoginFailureEvent(
      final UserIdCollectionMode mode,
      final String userId,
      final Boolean exists,
      final Map<String, String> metadata) {
    if (!isEnabled(mode)) {
      return;
    }
    dispatch(
        EVENTS.loginEvent(),
        (ctx, cb) -> cb.apply(ctx, mode, LOGIN_FAILURE.getSpanTag(), exists, userId, metadata));
  }

  public void onCustomEvent(
      final UserIdCollectionMode mode, final String eventName, final Map<String, String> metadata) {
    if (!isEnabled(mode)) {
      return;
    }
    dispatch(
        EVENTS.loginEvent(), (ctx, cb) -> cb.apply(ctx, mode, eventName, null, null, metadata));
  }

  private <T> void dispatch(
      final EventType<T> event, final BiFunction<RequestContext, T, Flow<Void>> consumer) {
    final AgentTracer.TracerAPI tracer = tracer();
    if (tracer == null) {
      return;
    }
    final CallbackProvider cbp = tracer.getCallbackProvider(RequestContextSlot.APPSEC);
    if (cbp == null) {
      return;
    }
    final AgentSpan span = tracer.activeSpan();
    if (span == null) {
      return;
    }
    final RequestContext ctx = span.getRequestContext();
    if (ctx == null) {
      return;
    }
    final T callback = cbp.getCallback(event);
    final Flow<Void> flow = consumer.apply(ctx, callback);
    if (flow == null) {
      return;
    }
    final Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      final BlockResponseFunction brf = ctx.getBlockResponseFunction();
      if (brf != null) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        brf.tryCommitBlockingResponse(
            ctx.getTraceSegment(),
            rba.getStatusCode(),
            rba.getBlockingContentType(),
            rba.getExtraHeaders());
      }
      throw new BlockingException("Blocked request (for user)");
    }
  }

  protected boolean isEnabled(final UserIdCollectionMode mode) {
    return ActiveSubsystems.APPSEC_ACTIVE && mode != DISABLED;
  }

  // Extract this to allow for easier testing
  protected AgentTracer.TracerAPI tracer() {
    return AgentTracer.get();
  }
}
