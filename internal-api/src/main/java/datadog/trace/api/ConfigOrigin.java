package datadog.trace.api;

// https://github.com/DataDog/instrumentation-telemetry-api-docs/blob/main/
// GeneratedDocumentation/ApiDocs/v2/SchemaDocumentation/Schemas/conf_key_value.md
public enum ConfigOrigin {
  /** configurations that are set through environment variables */
  ENV("env_var", 5),
  /** values that are set using remote config */
  REMOTE("remote_config", 9),
  /** configurations that are set through JVM properties */
  JVM_PROP("jvm_prop", 6),
  /** configuration read in the stable config file, managed by users */
  LOCAL_STABLE_CONFIG("local_stable_config", 4),
  /** configuration read in the stable config file, managed by fleet */
  FLEET_STABLE_CONFIG("fleet_stable_config", 7),
  /** configurations that are set through the customer application */
  CODE("code", 8),
  /** set by the dd.yaml file or json */
  DD_CONFIG("dd_config", 3),
  /** set for cases where it is difficult/not possible to determine the source of a config. */
  UNKNOWN("unknown", 2),
  /** set when the user has not set any configuration for the key (defaults to a value) */
  DEFAULT("default", 1);

  public final String value;
  public final int precedence;

  ConfigOrigin(String value, int precedence) {
    this.value = value;
    this.precedence = precedence;
  }
}
