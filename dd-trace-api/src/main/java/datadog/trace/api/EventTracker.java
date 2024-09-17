package datadog.trace.api;

import java.util.Map;

public class EventTracker {

  public static final EventTracker NO_EVENT_TRACKER = new EventTracker();

  /**
   * Method for tracking successful login event. A user login event is made of a user id, and an
   * optional key-value map of metadata of string types only.
   *
   * @param userId user id used for login
   * @param metadata custom metadata data represented as key/value map
   */
  public void trackLoginSuccessEvent(String userId, Map<String, String> metadata) {}

  /**
   * Method for tracking login failure event. A user login event is made of a user id, along user id
   * existing flag and an optional key-value map of metadata of string types only.
   *
   * @param userId user id used for login
   * @param exists flag indicates if provided userId exists
   * @param metadata custom metadata data represented as key/value map
   */
  public void trackLoginFailureEvent(String userId, boolean exists, Map<String, String> metadata) {}

  /**
   * Method for tracking custom events. A custom event is made of an event name along with an
   * optional key-value map of metadata of string types only
   *
   * @param eventName name of the custom event
   * @param metadata custom metadata data represented as key/value map
   */
  public void trackCustomEvent(String eventName, Map<String, String> metadata) {}
}
