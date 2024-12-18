package datadog.trace.api.telemetry;

public enum LoginEvent {
  LOGIN_SUCCESS("login.success", "login_success"),
  LOGIN_FAILURE("login.failure", "login_failure"),
  SIGN_UP("signup");

  private static final int numValues = LoginEvent.values().length;

  private final String spanTag;
  private final String telemetryTag;

  LoginEvent(final String tag) {
    this(tag, tag);
  }

  LoginEvent(final String spanTag, final String telemetryTag) {
    this.spanTag = spanTag;
    this.telemetryTag = telemetryTag;
  }

  public String getTelemetryTag() {
    return telemetryTag;
  }

  public String getSpanTag() {
    return spanTag;
  }

  public static int getNumValues() {
    return numValues;
  }
}
