package datadog.trace.api;

public enum ConfigOrigin {
  //  env_var: configurations that are set through environment variables
  //  code: configurations that are set through the customer application
  //  dd_config: set by the dd.yaml file or json
  //  remote_config: values that are set using remote config
  //  app.config: only applies to .NET
  //  default: set when the user has not set any configuration for the key (defaults to a value)
  //  unknown: set for cases where it is difficult/not possible to determine the source of a config.

  /** configurations that are set through environment variables */
  ENV("env_var"),
  /** values that are set using remote config */
  REMOTE("remote_config"),
  /** set when the user has not set any configuration for the key (defaults to a value) */
  DEFAULT("default");

  public final String value;

  ConfigOrigin(String value) {
    this.value = value;
  }
}
