package datadog.telemetry.api;

public enum RequestType {
  APP_STARTED("app-started"),
  APP_CLIENT_CONFIGURATION_CHANGE("app-client-configuration-change"),
  APP_DEPENDENCIES_LOADED("app-dependencies-loaded"),
  APP_INTEGRATIONS_CHANGE("app-integrations-change"),
  APP_HEARTBEAT("app-heartbeat"),
  APP_CLOSING("app-closing"),
  GENERATE_METRICS("generate-metrics"),
  LOGS("logs"),
  DISTRIBUTIONS("distributions");

  private final String value;

  RequestType(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return value;
  }
}
