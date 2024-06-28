package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.UserEventTrackingMode.DISABLED;
import static datadog.trace.api.UserEventTrackingMode.SAFE;

import datadog.trace.api.Config;
import datadog.trace.api.UserEventTrackingMode;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.Map;
import java.util.regex.Pattern;
import javax.annotation.Nonnull;

public class AppSecUserEventDecorator {

  private static final String NUMBER_PATTERN = "[0-9]+";
  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  private static final Pattern SAFE_USER_ID_PATTERN =
      Pattern.compile(NUMBER_PATTERN + "|" + UUID_PATTERN, Pattern.CASE_INSENSITIVE);

  public boolean isEnabled() {
    if (!ActiveSubsystems.APPSEC_ACTIVE) {
      return false;
    }
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    if (mode == DISABLED) {
      return false;
    }
    return true;
  }

  public void onUserNotFound() {
    if (!isEnabled()) {
      return;
    }
    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }
    segment.setTagTop("appsec.events.users.login.failure.usr.exists", false);
  }

  public void onLoginSuccess(String userId, Map<String, String> metadata) {
    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    onUserId(segment, "usr.id", userId);
    onEvent(segment, "users.login.success", metadata);
  }

  public void onLoginFailure(String userId, Map<String, String> metadata) {
    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    onUserId(segment, "appsec.events.users.login.failure.usr.id", userId);
    onEvent(segment, "users.login.failure", metadata);
  }

  public void onSignup(String userId, Map<String, String> metadata) {
    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    onUserId(segment, "usr.id", userId);
    onEvent(segment, "users.signup", metadata);
  }

  private void onEvent(@Nonnull TraceSegment segment, String eventName, Map<String, String> tags) {
    segment.setTagTop("appsec.events." + eventName + ".track", true, true);
    segment.setTagTop(Tags.ASM_KEEP, true);

    // Report user event tracking mode ("safe" or "extended")
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    if (mode != DISABLED) {
      segment.setTagTop("_dd.appsec.events." + eventName + ".auto.mode", mode.toString());
    }

    if (tags != null && !tags.isEmpty()) {
      segment.setTagTop("appsec.events." + eventName, tags);
    }
  }

  private void onUserId(final TraceSegment segment, final String tag, final String userId) {
    if (userId == null) {
      return;
    }
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    if (mode == SAFE && !SAFE_USER_ID_PATTERN.matcher(userId).matches()) {
      // do not set the user id if not numeric or UUID
      return;
    }
    segment.setTagTop(tag, userId);
  }

  protected TraceSegment getSegment() {
    return AgentTracer.get().getTraceSegment();
  }
}
