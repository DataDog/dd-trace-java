package datadog.trace.api.appsec;

import static datadog.trace.api.UserIdCollectionMode.SDK;

import datadog.trace.api.EventTracker;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.UserIdCollectionMode;
import java.util.Map;

public abstract class AppSecEventTracker extends EventTracker {

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
    onLoginSuccessEvent(SDK, userId, metadata);
  }

  @Override
  public final void trackLoginFailureEvent(
      String userId, boolean exists, Map<String, String> metadata) {
    onLoginFailureEvent(SDK, userId, exists, metadata);
  }

  @Override
  public final void trackCustomEvent(String eventName, Map<String, String> metadata) {
    onCustomEvent(SDK, eventName, metadata);
  }

  public abstract void onUserNotFound(UserIdCollectionMode mode);

  public abstract void onSignupEvent(
      UserIdCollectionMode mode, String userId, Map<String, String> metadata);

  public abstract void onLoginSuccessEvent(
      UserIdCollectionMode mode, String userId, Map<String, String> metadata);

  public abstract void onLoginFailureEvent(
      UserIdCollectionMode mode, String userId, Boolean exists, Map<String, String> metadata);

  public abstract void onUserEvent(UserIdCollectionMode mode, String userId);

  public abstract void onCustomEvent(
      UserIdCollectionMode mode, String eventName, Map<String, String> metadata);
}
