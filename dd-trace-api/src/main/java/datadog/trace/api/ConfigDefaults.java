package datadog.trace.api;

public final class ConfigDefaults {

  /* These fields are made public because they're referenced elsewhere internally.  They're not intended as public API. */
  public static final String DEFAULT_AGENT_HOST = "localhost";
  public static final int DEFAULT_TRACE_AGENT_PORT = 8126;
  public static final int DEFAULT_DOGSTATSD_PORT = 8125;
  public static final String DEFAULT_TRACE_AGENT_SOCKET_PATH = "/var/run/datadog/apm.socket";
  public static final String DEFAULT_DOGSTATSD_SOCKET_PATH = "/var/run/datadog/dsd.socket";
  public static final int DEFAULT_AGENT_TIMEOUT = 10; // timeout in seconds
  public static final String DEFAULT_SERVICE_NAME = "unnamed-java-app";
  public static final String DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME = "root-servlet";
  static final float DEFAULT_ANALYTICS_SAMPLE_RATE = 1.0f;
  public static final boolean DEFAULT_ASYNC_PROPAGATING = true;
  public static final int DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH = 512;

  private ConfigDefaults() {}
}
