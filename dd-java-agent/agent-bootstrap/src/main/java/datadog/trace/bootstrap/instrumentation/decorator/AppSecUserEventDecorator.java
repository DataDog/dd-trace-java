package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.UserEventTrackingMode.DISABLED;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.UserEventTrackingMode;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import javax.annotation.Nonnull;

public class AppSecUserEventDecorator {

  public void onLoginSuccess(String userId, Map<String, String> metadata) {
    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    if (userId != null) {
      segment.setTagTop("usr.id", userId);
    }
    onEvent(segment, "users.login.success", metadata);
  }

  public void onLoginFailure(String userId, Map<String, String> metadata, boolean userExists) {
    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    if (userId != null) {
      segment.setTagTop("appsec.events.users.login.failure.usr.id", userId);
    }
    segment.setTagTop("appsec.events.users.login.failure.usr.exists", userExists);
    onEvent(segment, "users.login.failure", metadata);
  }

  public void onSignup(String userId, Map<String, String> metadata) {
    TraceSegment segment = getSegment();
    if (segment == null) {
      return;
    }

    if (userId != null) {
      segment.setTagTop("usr.id", userId);
    }
    onEvent(segment, "users.signup", metadata);
  }

  private void onEvent(@Nonnull TraceSegment segment, String eventName, Map<String, String> tags) {
    segment.setTagTop("appsec.events." + eventName + ".track", true, true);
    segment.setTagTop(DDTags.MANUAL_KEEP, true);

    // Report user event tracking mode ("safe" or "extended")
    UserEventTrackingMode mode = Config.get().getAppSecUserEventsTrackingMode();
    if (mode != DISABLED) {
      segment.setTagTop("_dd.appsec.events." + eventName + ".auto.mode", mode.toString());
    }

    if (tags != null && !tags.isEmpty()) {
      segment.setTagTop("appsec.events." + eventName, tags);
    }
  }

  protected TraceSegment getSegment() {
    return AgentTracer.get().getTraceSegment();
  }
}
