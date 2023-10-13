package datadog.trace.api;

import datadog.trace.api.internal.InternalTracer;
import datadog.trace.api.internal.TraceSegment;
import java.util.Map;

public class EventTracker {

  public static final EventTracker NO_EVENT_TRACKER = new EventTracker(null);
  private final InternalTracer tracer;

  EventTracker(InternalTracer tracer) {
    this.tracer = tracer;
  }

  /**
   * Method for tracking successful login event. A user login event is made of a user id, and an
   * optional key-value map of metadata of string types only.
   *
   * @param userId user id used for login
   * @param metadata custom metadata data represented as key/value map
   */
  public void trackLoginSuccessEvent(String userId, Map<String, String> metadata) {
    if (userId == null || userId.isEmpty()) {
      throw new IllegalArgumentException("UserId is null or empty");
    }

    TraceSegment segment = getTraceSegment();
    if (segment == null) {
      return;
    }

    segment.setTagTop("_dd.appsec.events.users.login.success.sdk", true);
    segment.setTagTop("appsec.events.users.login.success.track", true);
    segment.setTagTop("usr.id", userId);
    segment.setTagTop(DDTags.MANUAL_KEEP, true);

    if (metadata != null && !metadata.isEmpty()) {
      segment.setTagTop("appsec.events.users.login.success", metadata);
    }
  }

  /**
   * Method for tracking login failure event. A user login event is made of a user id, along user id
   * existing flag and an optional key-value map of metadata of string types only.
   *
   * @param userId user id used for login
   * @param exists flag indicates if provided userId exists
   * @param metadata custom metadata data represented as key/value map
   */
  public void trackLoginFailureEvent(String userId, boolean exists, Map<String, String> metadata) {
    if (userId == null || userId.isEmpty()) {
      throw new IllegalArgumentException("UserId is null or empty");
    }

    if (tracer == null) {
      return;
    }

    TraceSegment segment = getTraceSegment();
    if (segment == null) {
      return;
    }

    segment.setTagTop("_dd.appsec.events.users.login.failure.sdk", true);
    segment.setTagTop("appsec.events.users.login.failure.track", true);
    segment.setTagTop("appsec.events.users.login.failure.usr.id", userId);
    segment.setTagTop("appsec.events.users.login.failure.usr.exists", exists);
    segment.setTagTop(DDTags.MANUAL_KEEP, true);

    if (metadata != null && !metadata.isEmpty()) {
      segment.setTagTop("appsec.events.users.login.failure", metadata);
    }
  }

  /**
   * Method for tracking custom events. A custom event is made of an event name along with an
   * optional key-value map of metadata of string types only
   *
   * @param eventName name of the custom event
   * @param metadata custom metadata data represented as key/value map
   */
  public void trackCustomEvent(String eventName, Map<String, String> metadata) {
    if (eventName == null || eventName.isEmpty()) {
      throw new IllegalArgumentException("EventName is null or empty");
    }

    if (tracer == null) {
      return;
    }

    TraceSegment segment = getTraceSegment();
    if (segment == null) {
      return;
    }

    segment.setTagTop("_dd.appsec.events." + eventName + ".sdk", true);
    segment.setTagTop("appsec.events." + eventName + ".track", true, true);
    segment.setTagTop(DDTags.MANUAL_KEEP, true);

    if (metadata != null && !metadata.isEmpty()) {
      segment.setTagTop("appsec.events." + eventName, metadata, true);
    }
  }

  private TraceSegment getTraceSegment() {
    if (tracer == null) {
      return null;
    }
    return tracer.getTraceSegment();
  }
}
