package datadog.appsec.api.user;

import java.util.Map;

public class User {

  private static volatile UserService SERVICE = UserService.NO_OP;

  /**
   * Controls the implementation for user service. The AppSec subsystem calls this method on
   * startup. This can be called explicitly for e.g. testing purposes.
   *
   * @param service the implementation for the user service.
   */
  public static void setUserService(final UserService service) {
    SERVICE = service;
  }

  /**
   * Sets the user monitoring tags on the root span using the prefix {@code usr}
   *
   * @param id identifier of the user
   * @param metadata custom metadata data represented as key/value map
   */
  public static void setUser(final String id, final Map<String, String> metadata) {
    SERVICE.trackUserEvent(id, metadata);
  }
}
