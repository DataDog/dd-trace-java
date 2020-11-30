package datadog.trace.api.config;

/**
 * A list of keys to be used in a Properties instance with dd-trace-ot's DDTracer as follows:
 *
 * <pre>
 *   DDTracer.builder().withProperties(new Properties()).build()
 * </pre>
 *
 * If using dd-java-agent, these keys represent settings that should be configured via system
 * properties, environment variables, or config properties file. See online documentation for
 * details.
 */
public final class GeneralConfig {

  public static final String CONFIGURATION_FILE = "trace.config";
  public static final String API_KEY = "api-key";
  public static final String API_KEY_FILE = "api-key-file";
  public static final String SITE = "site";

  public static final String SERVICE_NAME = "service.name";
  public static final String ENV = "env";
  public static final String VERSION = "version";
  public static final String TAGS = "tags";
  @Deprecated // Use dd.tags instead
  public static final String GLOBAL_TAGS = "trace.global.tags";

  public static final String RUNTIME_METRICS_ENABLED = "runtime.metrics.enabled";

  public static final String HEALTH_METRICS_ENABLED = "trace.health.metrics.enabled";
  public static final String HEALTH_METRICS_STATSD_HOST = "trace.health.metrics.statsd.host";
  public static final String HEALTH_METRICS_STATSD_PORT = "trace.health.metrics.statsd.port";
  public static final String PERF_METRICS_ENABLED = "trace.perf.metrics.enabled";

  public static final String TRACER_METRICS_ENABLED = "trace.tracer.metrics.enabled";
  public static final String TRACER_METRICS_MAX_AGGREGATES = "trace.tracer.metrics.max.aggregates";
  public static final String TRACER_METRICS_MAX_PENDING = "trace.tracer.metrics.max.pending";

  public static final String INTERNAL_EXIT_ON_FAILURE = "trace.internal.exit.on.failure";

  private GeneralConfig() {}
}
