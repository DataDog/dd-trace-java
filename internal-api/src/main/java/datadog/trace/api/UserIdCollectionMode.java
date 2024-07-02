package datadog.trace.api;

public enum UserIdCollectionMode {
  IDENTIFICATION("identification", "ident"),
  ANONYMIZATION("anonymization", "anon"),
  DISABLED("disabled");

  private static UserIdCollectionMode CURRENT_MODE = IDENTIFICATION;

  private final String[] values;

  UserIdCollectionMode(final String... values) {
    this.values = values;
  }

  public static UserIdCollectionMode fromString(String collectionMode, String trackingMode) {
    if (collectionMode == null && trackingMode != null) {
      CURRENT_MODE = fromTracking(trackingMode);
    } else {
      CURRENT_MODE = fromMode(collectionMode);
    }
    return CURRENT_MODE;
  }

  @SuppressWarnings("UnusedReturnValue")
  public static UserIdCollectionMode fromRemoteConfig(final String mode) {
    if (mode == null) {
      // restore default
      CURRENT_MODE = Config.get().getAppSecUserIdCollectionMode();
    } else {
      CURRENT_MODE = fromMode(mode);
    }
    return CURRENT_MODE;
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

  @Override
  public String toString() {
    return values[0];
  }

  public static UserIdCollectionMode get() {
    return CURRENT_MODE;
  }
}
