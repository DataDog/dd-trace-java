package datadog.trace.api;

import java.util.concurrent.atomic.AtomicReference;

public enum UserIdCollectionMode {
  IDENTIFICATION("identification", "ident"),
  ANONYMIZATION("anonymization", "anon"),
  DISABLED("disabled"),
  SDK("sdk");

  private static final AtomicReference<UserIdCollectionMode> CURRENT_MODE =
      new AtomicReference<>(IDENTIFICATION);

  private final String[] values;

  UserIdCollectionMode(final String... values) {
    this.values = values;
  }

  public static UserIdCollectionMode fromString(String collectionMode, String trackingMode) {
    return CURRENT_MODE.updateAndGet(
        current -> {
          if (collectionMode == null && trackingMode != null) {
            return fromTracking(trackingMode);
          } else {
            return fromMode(collectionMode);
          }
        });
  }

  public static UserIdCollectionMode fromRemoteConfig(final String mode) {
    return CURRENT_MODE.updateAndGet(
        current -> {
          if (mode == null) {
            // use locally configured value
            return Config.get().getAppSecUserIdCollectionMode();
          } else {
            return fromMode(mode);
          }
        });
  }

  private static UserIdCollectionMode fromMode(String mode) {
    if (mode == null || IDENTIFICATION.matches(mode)) {
      return IDENTIFICATION;
    } else if (ANONYMIZATION.matches(mode)) {
      return ANONYMIZATION;
    }
    return DISABLED;
  }

  private static UserIdCollectionMode fromTracking(String tracking) {
    switch (UserEventTrackingMode.fromString(tracking)) {
      case SAFE:
        return ANONYMIZATION;
      case EXTENDED:
        return IDENTIFICATION;
      default:
        return DISABLED;
    }
  }

  private boolean matches(final String mode) {
    for (String value : values) {
      if (value.equalsIgnoreCase(mode)) {
        return true;
      }
    }
    return false;
  }

  public String fullName() {
    return values[0];
  }

  public String shortName() {
    return values[values.length - 1];
  }

  public static UserIdCollectionMode get() {
    return CURRENT_MODE.get();
  }
}
