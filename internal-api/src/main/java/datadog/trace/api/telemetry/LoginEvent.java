package datadog.trace.api.telemetry;

public enum LoginEvent {
  LOGIN_SUCCESS("login_success"),
  LOGIN_FAILURE("login_failure"),
  SIGN_UP("signup"),
  CUSTOM("custom");

  private static final int numValues = LoginEvent.values().length;

  private final String tag;

  LoginEvent(final String tag) {
    this.tag = tag;
  }

  public String getTag() {
    return tag;
  }

  public static int getNumValues() {
    return numValues;
  }
}
