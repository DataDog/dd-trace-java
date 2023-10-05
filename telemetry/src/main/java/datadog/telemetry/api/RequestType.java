package datadog.telemetry.api;

public enum RequestType {
  APP_STARTED("app-started"),
  APP_DEPENDENCIES_LOADED("app-dependencies-loaded"),
  APP_INTEGRATIONS_CHANGE("app-integrations-change"),
  APP_CLIENT_CONFIGURATION_CHANGE("app-client-configuration-change"),
  // app-product-change
  APP_HEARTBEAT("app-heartbeat"),
  // app-extended-heartbeat
  APP_CLOSING("app-closing"),
  GENERATE_METRICS("generate-metrics"),
  LOGS("logs"),
  DISTRIBUTIONS("distributions"),
  // apm-onboarding-event -
  // https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/payload.md#if-request_type--apm-onboarding-event-we-add-the-following-to-payload
  // apm-remote-config-event -
  // https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/payload.md#if-request_type--apm-remote-config-event-we-add-the-following-to-payload
  MESSAGE_BATCH("message-batch");

  private final String value;

  RequestType(String value) {
    this.value = value;
  }

  @Override
  public String toString() {
    return value;
  }
}
