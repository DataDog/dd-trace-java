package datadog.trace.api.appsec;

import static datadog.trace.api.UserIdCollectionMode.ANONYMIZATION;
import static datadog.trace.api.UserIdCollectionMode.DISABLED;
import static datadog.trace.api.UserIdCollectionMode.SDK;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.api.telemetry.LoginEvent.CUSTOM;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_FAILURE;
import static datadog.trace.api.telemetry.LoginEvent.LOGIN_SUCCESS;
import static datadog.trace.api.telemetry.LoginVersion.AUTO;
import static datadog.trace.api.telemetry.LoginVersion.V1;
import static datadog.trace.api.telemetry.LoginVersion.V2;
import static datadog.trace.util.Strings.toHexString;
import static java.util.Collections.emptyMap;

import datadog.appsec.api.blocking.BlockingException;
import datadog.appsec.api.login.EventTrackerService;
import datadog.appsec.api.login.EventTrackerV2;
import datadog.appsec.api.user.User;
import datadog.appsec.api.user.UserService;
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
import datadog.trace.api.telemetry.LoginEvent;
import datadog.trace.api.telemetry.LoginVersion;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

public class AppSecEventTracker extends EventTracker implements UserService, EventTrackerService {

  private static final int HASH_SIZE_BYTES = 16; // 128 bits
  private static final String ANON_PREFIX = "anon_";

  private static final Map<String, LoginEvent> EVENT_MAPPING;

  private static final String LOGIN_SUCCESS_EVENT = "users.login.success";
  private static final String LOGIN_FAILURE_EVENT = "users.login.failure";
  private static final String SIGNUP_EVENT = "users.signup";

  static {
    EVENT_MAPPING = new HashMap<>();
    EVENT_MAPPING.put(LOGIN_SUCCESS_EVENT, LOGIN_SUCCESS);
    EVENT_MAPPING.put(LOGIN_FAILURE_EVENT, LoginEvent.LOGIN_FAILURE);
    EVENT_MAPPING.put(SIGNUP_EVENT, LoginEvent.SIGN_UP);
  }

  private static final String COLLECTION_MODE = "_dd.appsec.user.collection_mode";

  public static void install() {
    final AppSecEventTracker tracker = new AppSecEventTracker();
    GlobalTracer.setEventTracker(tracker);
    EventTrackerV2.setEventTrackerService(tracker);
    User.setUserService(tracker);
  }

  @Override
  public final void trackLoginSuccessEvent(String userId, Map<String, String> metadata) {
    if (userId == null || userId.isEmpty()) {
      throw new IllegalArgumentException("userId is null or empty");
    }
    WafMetricCollector.get().appSecSdkEvent(LOGIN_SUCCESS, V1);
    if (handleLoginEvent(V1, LOGIN_SUCCESS_EVENT, SDK, userId, userId, null, metadata)) {
      throw new BlockingException("Blocked request (for login success)");
    }
  }

  @Override
  public final void trackLoginFailureEvent(
      String userId, boolean exists, Map<String, String> metadata) {
    if (userId == null || userId.isEmpty()) {
      throw new IllegalArgumentException("userId is null or empty");
    }
    WafMetricCollector.get().appSecSdkEvent(LOGIN_FAILURE, V1);
    if (handleLoginEvent(V1, LOGIN_FAILURE_EVENT, SDK, userId, userId, exists, metadata)) {
      throw new BlockingException("Blocked request (for login failure)");
    }
  }

  @Override
  public void trackUserLoginSuccess(
      final String login, final String userId, final Map<String, String> metadata) {
    if (login == null || login.isEmpty()) {
      throw new IllegalArgumentException("login is null or empty");
    }
    WafMetricCollector.get().appSecSdkEvent(LOGIN_SUCCESS, V2);
    if (handleLoginEvent(V2, LOGIN_SUCCESS_EVENT, SDK, login, userId, null, metadata)) {
      throw new BlockingException("Blocked request (for login success)");
    }
  }

  @Override
  public void trackUserLoginFailure(
      final String login, final boolean exists, final Map<String, String> metadata) {
    if (login == null || login.isEmpty()) {
      throw new IllegalArgumentException("login is null or empty");
    }
    WafMetricCollector.get().appSecSdkEvent(LOGIN_FAILURE, V2);
    if (handleLoginEvent(V2, LOGIN_FAILURE_EVENT, SDK, login, null, exists, metadata)) {
      throw new BlockingException("Blocked request (for login failure)");
    }
  }

  @SuppressWarnings("deprecation")
  @Override
  public final void trackCustomEvent(String eventName, Map<String, String> metadata) {
    if (eventName == null || eventName.isEmpty()) {
      throw new IllegalArgumentException("eventName is null or empty");
    }
    WafMetricCollector.get().appSecSdkEvent(CUSTOM, V2);
    if (handleLoginEvent(V2, eventName, SDK, null, null, null, metadata)) {
      throw new BlockingException("Blocked request (for custom event)");
    }
  }

  @Override
  public void trackUserEvent(final String userId, final Map<String, String> metadata) {
    if (userId == null || userId.isEmpty()) {
      throw new IllegalArgumentException("userId is null or empty");
    }
    if (handleUser(SDK, userId, metadata)) {
      throw new BlockingException("Blocked request (for user)");
    }
  }

  public void onUserEvent(final UserIdCollectionMode mode, final String userId) {
    onUserEvent(mode, userId, emptyMap());
  }

  public void onUserEvent(
      final UserIdCollectionMode mode, final String userId, final Map<String, String> metadata) {
    if (handleUser(mode, userId, metadata)) {
      throw new BlockingException("Blocked request (for user)");
    }
  }

  public void onUserNotFound(final UserIdCollectionMode mode) {
    if (handleLoginEvent(AUTO, LOGIN_FAILURE_EVENT, mode, null, null, false, null)) {
      throw new BlockingException("Blocked request (for user not found)");
    }
  }

  public void onSignupEvent(
      final UserIdCollectionMode mode, final String login, final Map<String, String> metadata) {
    onSignupEvent(mode, login, null, metadata);
  }

  public void onSignupEvent(
      final UserIdCollectionMode mode,
      final String login,
      final String userId,
      final Map<String, String> metadata) {
    if (handleLoginEvent(AUTO, SIGNUP_EVENT, mode, login, userId, null, metadata)) {
      throw new BlockingException("Blocked request (for signup)");
    }
  }

  public void onLoginSuccessEvent(
      final UserIdCollectionMode mode, final String login, final Map<String, String> metadata) {
    onLoginSuccessEvent(mode, login, null, metadata);
  }

  public void onLoginSuccessEvent(
      final UserIdCollectionMode mode,
      final String login,
      final String user,
      final Map<String, String> metadata) {
    if (handleLoginEvent(AUTO, LOGIN_SUCCESS_EVENT, mode, login, user, null, metadata)) {
      throw new BlockingException("Blocked request (for login success)");
    }
  }

  public void onLoginFailureEvent(
      final UserIdCollectionMode mode,
      final String login,
      final Boolean exists,
      final Map<String, String> metadata) {
    if (handleLoginEvent(AUTO, LOGIN_FAILURE_EVENT, mode, login, null, exists, metadata)) {
      throw new BlockingException("Blocked request (for login failure)");
    }
  }

  /**
   * Takes care of the logged-in user and returns {@code true} if execution must be halted due to a
   * blocking action
   */
  private boolean handleUser(
      final UserIdCollectionMode mode, final String userId, final Map<String, String> metadata) {
    if (!isEnabled(mode)) {
      return false;
    }
    final AgentTracer.TracerAPI tracer = tracer();
    if (tracer == null) {
      return false;
    }
    final TraceSegment segment = tracer.getTraceSegment();
    if (segment == null) {
      return false;
    }
    final String finalUserId = anonymize(mode, userId);
    if (finalUserId == null) {
      return false; // could not anonymize the user
    }
    if (mode != SDK) {
      segment.setTagTop("_dd.appsec.usr.id", finalUserId);
    }
    if (isNewUser(mode, segment)) {
      segment.setTagTop("usr.id", finalUserId);
      if (metadata != null && !metadata.isEmpty()) {
        segment.setTagTop("usr", metadata);
      }
      segment.setTagTop(COLLECTION_MODE, mode.fullName());
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);
      return dispatch(tracer, EVENTS.user(), (ctx, cb) -> cb.apply(ctx, finalUserId));
    }
    return false;
  }

  /**
   * Takes care of a login event and returns {@code true} if execution must be halted due to a
   * blocking action
   */
  private boolean handleLoginEvent(
      final LoginVersion version,
      final String eventName,
      final UserIdCollectionMode mode,
      final String login,
      final String userId,
      final Boolean exists,
      final Map<String, String> metadata) {
    if (!isEnabled(mode)) {
      return false;
    }
    final AgentTracer.TracerAPI tracer = tracer();
    if (tracer == null) {
      return false;
    }
    final TraceSegment segment = tracer.getTraceSegment();
    if (segment == null) {
      return false;
    }
    boolean block = false;
    final String finalLogin = anonymize(mode, login);
    if (finalLogin == null && login != null) {
      return false; // could not anonymize the login
    }
    if (mode == SDK) {
      segment.setTagTop("_dd.appsec.events." + eventName + ".sdk", true, true);
    } else {
      if (finalLogin != null) {
        segment.setTagTop("_dd.appsec.usr.login", finalLogin);
      }
      segment.setTagTop("_dd.appsec.events." + eventName + ".auto.mode", mode.fullName(), true);
    }
    final LoginEvent event = EVENT_MAPPING.get(eventName);
    if (isNewLoginEvent(mode, segment, eventName)) {
      if (finalLogin != null) {
        segment.setTagTop("appsec.events." + eventName + ".usr.login", finalLogin, true);
      }
      if (metadata != null && !metadata.isEmpty()) {
        segment.setTagTop("appsec.events." + eventName, metadata, true);
      }
      if (exists != null) {
        segment.setTagTop("appsec.events." + eventName + ".usr.exists", exists, true);
      }
      segment.setTagTop("appsec.events." + eventName + ".track", true, true);
      segment.setTagTop(Tags.ASM_KEEP, true);
      segment.setTagTop(Tags.PROPAGATED_TRACE_SOURCE, ProductTraceSource.ASM);

      if (finalLogin != null && event != null) {
        block =
            dispatch(tracer, EVENTS.loginEvent(), (ctx, cb) -> cb.apply(ctx, event, finalLogin));
      }
    }
    if (userId != null) {
      boolean blockUser;
      if (version == V2) {
        segment.setTagTop("appsec.events." + eventName + ".usr.id", userId, true);
        if (metadata != null && !metadata.isEmpty()) {
          segment.setTagTop("appsec.events." + eventName + ".usr", metadata, true);
        }
        blockUser = handleUser(mode, userId, metadata);
      } else {
        if (event == LOGIN_SUCCESS) {
          segment.setTagTop("usr.id", userId);
        } else {
          segment.setTagTop("appsec.events." + eventName + ".usr.id", userId, true);
        }
        blockUser = dispatch(tracer, EVENTS.user(), (ctx, cb) -> cb.apply(ctx, userId));
      }
      block |= blockUser;
    }
    return block;
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
    final Object value = segment.getTagTop(COLLECTION_MODE);
    return value == null || !"sdk".equalsIgnoreCase(value.toString());
  }

  /**
   * Dispatch the selected event and return {@code true} if the execution must be halted due to a
   * block action
   */
  private <T> boolean dispatch(
      final AgentTracer.TracerAPI tracer,
      final EventType<T> event,
      final BiFunction<RequestContext, T, Flow<Void>> consumer) {
    if (tracer == null) {
      return false;
    }
    final CallbackProvider cbp = tracer.getCallbackProvider(RequestContextSlot.APPSEC);
    if (cbp == null) {
      return false;
    }
    final AgentSpan span = tracer.activeSpan();
    if (span == null) {
      return false;
    }
    final RequestContext ctx = span.getRequestContext();
    if (ctx == null) {
      return false;
    }
    final T callback = cbp.getCallback(event);
    if (callback == null) {
      return false;
    }
    final Flow<Void> flow = consumer.apply(ctx, callback);
    if (flow == null) {
      return false;
    }
    final Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      final BlockResponseFunction brf = ctx.getBlockResponseFunction();
      if (brf != null) {
        Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
        brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
      }
      return true;
    }
    return false;
  }

  protected static String anonymize(final UserIdCollectionMode mode, final String userId) {
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
