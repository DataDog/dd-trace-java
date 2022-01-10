package datadog.trace.api.config;

/**
 * These config options will only work with dd-java-agent, not with dd-trace-ot.
 *
 * <p>Configure via system properties, environment variables, or config properties file. See online
 * documentation for details.
 */
public final class JmxFetchConfig {
  public static final String JMX_TAGS = "trace.jmx.tags";
  public static final String JMX_FETCH_ENABLED = "jmxfetch.enabled";
  public static final String JMX_FETCH_START_DELAY = "jmxfetch.start-delay";
  public static final String JMX_FETCH_CONFIG_DIR = "jmxfetch.config.dir";
  public static final String JMX_FETCH_CONFIG = "jmxfetch.config";
  @Deprecated public static final String JMX_FETCH_METRICS_CONFIGS = "jmxfetch.metrics-configs";
  public static final String JMX_FETCH_CHECK_PERIOD = "jmxfetch.check-period";
  public static final String JMX_FETCH_INITIAL_REFRESH_BEANS_PERIOD =
      "jmxfetch.initial-refresh-beans-period";
  public static final String JMX_FETCH_REFRESH_BEANS_PERIOD = "jmxfetch.refresh-beans-period";
  public static final String JMX_FETCH_STATSD_HOST = "jmxfetch.statsd.host";
  public static final String JMX_FETCH_STATSD_PORT = "jmxfetch.statsd.port";
  public static final String JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED =
      "jmxfetch.multiple-runtime-services.enabled";
  public static final String JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT =
      "jmxfetch.multiple-runtime-services.limit";

  private JmxFetchConfig() {}
}
