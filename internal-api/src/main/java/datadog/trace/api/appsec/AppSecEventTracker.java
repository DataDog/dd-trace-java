package datadog.trace.api.appsec;

import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION;
import static datadog.trace.api.UserIdCollectionMode.DISABLED;
import static datadog.trace.api.UserIdCollectionMode.SDK;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS;
import static datadog.trace.api.telemetry.LoginEvent.SIGN_UP;
import static datadog.trace.util.Strings.toHexString;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.EventTracker;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.ProductTraceSource;
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
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.function.BiFunction;

public class AppSecEventTracker extends EventTracker {

  private static final int HASH_SIZE_BYTES = 16; // 128 bits
  private static final String ANON_PREFIX = "anon_";

  private static final String LOGIN_SUCCESS_TAG = "users.login.success";
  private static final String LOGIN_FAILURE_TAG = "users.login.failure";
  private static final String SIGNUP_TAG = "users.signup";

  public static void install() {
    GlobalTracer.setEventTracker(new AppSecEventTracker());
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
    if (isNewLoginEvent(mode, segment, LOGIN_FAILURE_TAG)) {
      segment.setTagTop("appsec.events.users.login.failure.usr.exists", false);
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
    }
  }

  public void onUserEvent(final UserIdCollectionMode mode, final String userId) {
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
    final String finalUserId = anonymizeUser(mode, userId);
    if (finalUserId == null) {
      return;
    }
    if (mode != SDK) {
      segment.setTagTop("_dd.appsec.usr.id", finalUserId);
    }
    if (isNewUser(mode, segment)) {
      segment.setTagTop("usr.id", finalUserId);
      segment.setTagTop("_dd.appsec.user.collection_mode", mode.fullName());
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      dispatch(tracer, EVENTS.user(), (ctx, cb) -> cb.apply(ctx, finalUserId));
    }
  }

  public void onSignupEvent(
      final UserIdCollectionMode mode, final String userId, final Map<String, String> metadata) {
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
    final String finalUserId = anonymizeUser(mode, userId);
    if (finalUserId == null) {
      return;
    }

    if (mode == SDK) {
      // TODO update SDK separating usr.login / usr.id
      segment.setTagTop("appsec.events.users.signup.usr.id", finalUserId);
      segment.setTagTop("_dd.appsec.events.users.signup.sdk", true);
    } else {
      segment.setTagTop("_dd.appsec.usr.login", finalUserId);
      segment.setTagTop("_dd.appsec.events.users.signup.auto.mode", mode.fullName());
    }
    if (isNewLoginEvent(mode, segment, SIGNUP_TAG)) {
      segment.setTagTop("appsec.events.users.signup.usr.login", finalUserId);
      if (metadata != null && !metadata.isEmpty()) {
        segment.setTagTop("appsec.events.users.signup", metadata);
      }
      segment.setTagTop("appsec.events.users.signup.track", true);
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      dispatch(
          tracer,
          EVENTS.loginEvent(),
          (ctx, callback) -> callback.apply(ctx, SIGN_UP, finalUserId));
      if (mode == SDK) {
        dispatch(tracer, EVENTS.user(), (ctx, callback) -> callback.apply(ctx, finalUserId));
      }
    }
  }

  public void onLoginSuccessEvent(
      final UserIdCollectionMode mode, final String userId, final Map<String, String> metadata) {
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
    final String finalUserId = anonymizeUser(mode, userId);
    if (finalUserId == null) {
      return;
    }
    if (mode == SDK) {
      // TODO update SDK separating usr.login / usr.id
      segment.setTagTop("usr.id", finalUserId);
      segment.setTagTop("_dd.appsec.events.users.login.success.sdk", true);
    } else {
      segment.setTagTop("_dd.appsec.usr.login", finalUserId);
      segment.setTagTop("_dd.appsec.events.users.login.success.auto.mode", mode.fullName());
    }
    if (isNewLoginEvent(mode, segment, LOGIN_SUCCESS_TAG)) {
      segment.setTagTop("appsec.events.users.login.success.usr.login", finalUserId);
      if (metadata != null && !metadata.isEmpty()) {
        segment.setTagTop("appsec.events.users.login.success", metadata);
      }
      segment.setTagTop("appsec.events.users.login.success.track", true);
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      dispatch(
          tracer,
          EVENTS.loginEvent(),
          (ctx, callback) -> callback.apply(ctx, LOGIN_SUCCESS, finalUserId));
      if (mode == SDK) {
        dispatch(tracer, EVENTS.user(), (ctx, callback) -> callback.apply(ctx, finalUserId));
      }
    }
  }

  public void onLoginFailureEvent(
      final UserIdCollectionMode mode,
      final String userId,
      final Boolean exists,
      final Map<String, String> metadata) {
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
    final String finalUserId = anonymizeUser(mode, userId);
    if (finalUserId == null) {
      return;
    }

    if (mode == SDK) {
      // TODO update SDK separating usr.login / usr.id
      segment.setTagTop("appsec.events.users.login.failure.usr.id", finalUserId);
      segment.setTagTop("_dd.appsec.events.users.login.failure.sdk", true);
    } else {
      segment.setTagTop("_dd.appsec.usr.login", finalUserId);
      segment.setTagTop("_dd.appsec.events.users.login.failure.auto.mode", mode.fullName());
    }
    if (isNewLoginEvent(mode, segment, LOGIN_FAILURE_TAG)) {
      segment.setTagTop("appsec.events.users.login.failure.usr.login", finalUserId);
      if (metadata != null && !metadata.isEmpty()) {
        segment.setTagTop("appsec.events.users.login.failure", metadata);
      }
      if (exists != null) {
        segment.setTagTop("appsec.events.users.login.failure.usr.exists", exists);
      }
      segment.setTagTop("appsec.events.users.login.failure.track", true);
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      dispatch(
          tracer,
          EVENTS.loginEvent(),
          (ctx, callback) -> callback.apply(ctx, LOGIN_FAILURE, finalUserId));
      if (mode == SDK) {
        dispatch(tracer, EVENTS.user(), (ctx, callback) -> callback.apply(ctx, finalUserId));
      }
    }
  }

  public void onCustomEvent(
      final UserIdCollectionMode mode, final String eventName, final Map<String, String> metadata) {
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
    if (mode == SDK) {
      segment.setTagTop("_dd.appsec.events." + eventName + ".sdk", true, true);
    }
    if (isNewLoginEvent(mode, segment, eventName)) {
      if (metadata != null && !metadata.isEmpty()) {
        segment.setTagTop("appsec.events." + eventName, metadata, true);
      }
      segment.setTagTop("appsec.events." + eventName + ".track", true, true);
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
    }
  }

  private boolean isNewLoginEvent(
      final UserIdCollectionMode mode, final TraceSegment segment, final String event) {
    if (mode == SDK) {
      return true;
    }
    return segment.getTagTop("_dd.appsec.events." + event + ".sdk") == null;
  }

  private boolean isNewUser(final UserIdCollectionMode mode, final TraceSegment segment) {
    if (mode == SDK) {
      return true;
    }
    final Object value = segment.getTagTop("_dd.appsec.user.collection_mode");
    return value == null || !"sdk".equalsIgnoreCase(value.toString());
  }

  private <T> void dispatch(
      final AgentTracer.TracerAPI tracer,
      final EventType<T> event,
      final BiFunction<RequestContext, T, Flow<Void>> consumer) {
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
    if (callback == null) {
      return;
    }
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

  protected static String anonymizeUser(final UserIdCollectionMode mode, final String userId) {
    if (mode != ANONYMIZATION || userId == null) {
      return userId;
    }
    MessageDigest digest;
    try {
      // TODO avoid lookup a new instance every time
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      return null;
    }
    digest.update(userId.getBytes());
    byte[] hash = digest.digest();
    if (hash.length > HASH_SIZE_BYTES) {
      byte[] temp = new byte[HASH_SIZE_BYTES];
      System.arraycopy(hash, 0, temp, 0, temp.length);
      hash = temp;
    }
    return ANON_PREFIX + toHexString(hash);
  }

  protected boolean isEnabled(final UserIdCollectionMode mode) {
    return mode == SDK || (ActiveSubsystems.APPSEC_ACTIVE && mode != DISABLED);
  }

  // Extract this to allow for easier testing
  protected AgentTracer.TracerAPI tracer() {
    return AgentTracer.get();
  }
}
