package datadog.telemetry.api;

public enum RequestType {
  APP_STARTED("app-started"),
  APP_DEPENDENCIES_LOADED("app-dependencies-loaded"),
  APP_INTEGRATIONS_CHANGE("app-integrations-change"),
  APP_CLIENT_CONFIGURATION_CHANGE("app-client-configuration-change"),
  APP_HEARTBEAT("app-heartbeat"),
  APP_EXTENDED_HEARTBEAT("app-extended-heartbeat"),
  APP_CLOSING("app-closing"),
  APP_PRODUCT_CHANGE("app-product-change"),
  GENERATE_METRICS("generate-metrics"),
  LOGS("logs"),
  DISTRIBUTIONS("distributions"),
  MESSAGE_BATCH("message-batch"),
  APP_ENDPOINTS("app-endpoints");

  private final String value;

  RequestType(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return value;
  }
}
