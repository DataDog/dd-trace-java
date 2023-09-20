package datadog.trace.api;

public enum ConfigOrigin {
  /** configurations that are set through environment variables */
  ENV("env_var"),
  /** values that are set using remote config */
  REMOTE("remote_config"),
  /** configurations that are set through JVM properties */
  JVM_PROP("jvm_prop"),
  /** set when the user has not set any configuration for the key (defaults to a value) */
  DEFAULT("default");

  public final String value;

  ConfigOrigin(String value) {
    this.value = value;
  }
}
