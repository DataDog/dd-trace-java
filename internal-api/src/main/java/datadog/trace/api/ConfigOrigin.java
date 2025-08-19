package datadog.trace.api;

// https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/
// GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/conf_key_value.md
public enum ConfigOrigin {
  /** configurations that are set through environment variables */
  ENV("env_var"),
  /** values that are set using remote config */
  REMOTE("remote_config"),
  /** configurations that are set through JVM properties */
  JVM_PROP("jvm_prop"),
  /** configuration read in the stable config file, managed by users */
  LOCAL_STABLE_CONFIG("local_stable_config"),
  /** configuration read in the stable config file, managed by fleet */
  FLEET_STABLE_CONFIG("fleet_stable_config"),
  /** configurations that are set through the customer application */
  CODE("code"),
  /** set by the dd.yaml file or json */
  DD_CONFIG("dd_config"),
  /** set for cases where it is difficult/not possible to determine the source of a config. */
  UNKNOWN("unknown"),
  /** set when the config is calculated from multiple configs */
  CALCULATED("calculated"),
  /** set when the user has not set any configuration for the key (defaults to a value) */
  DEFAULT("default");

  public final String value;

  ConfigOrigin(String value) {
    this.value = value;
  }
}
