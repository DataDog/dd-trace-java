package datadog.appsec.api.login;

import java.util.Map;

public class EventTrackerV2 {

  private static volatile EventTrackerService SERVICE = EventTrackerService.NO_OP;

  /**
   * Controls the implementation for service. The AppSec subsystem calls this method on startup.
   * This can be called explicitly for e.g. testing purposes.
   *
   * @param service the implementation for the user service.
   */
  public static void setEventTrackerService(final EventTrackerService service) {
    SERVICE = service;
  }

  /**
   * Tracks a successful user login event.
   *
   * @param login the non-null login data (e.g., username or email) used for authentication.
   * @param userId an optional user ID string; can be null.
   * @param metadata optional metadata for the login event; can be null.
   */
  public static void trackUserLoginSuccess(
      final String login, final String userId, Map<String, String> metadata) {
    SERVICE.trackUserLoginSuccess(login, userId, metadata);
  }

  /**
   * Tracks a failed user login event.
   *
   * @param login the non-null login data (e.g., username or email) used for authentication.
   * @param exists indicates whether the provided login identifier corresponds to an existing user.
   * @param metadata optional metadata for the login event; can be null.
   */
  public static void trackUserLoginFailure(
      String login, boolean exists, Map<String, String> metadata) {
    SERVICE.trackUserLoginFailure(login, exists, metadata);
  }

  /**
   * Method for tracking custom events.
   *
   * @param eventName name of the custom event
   * @param metadata custom metadata data represented as key/value map
   */
  public static void trackCustomEvent(String eventName, Map<String, String> metadata) {
    SERVICE.trackCustomEvent(eventName, metadata);
  }
}
