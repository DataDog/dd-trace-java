package datadog.trace.api.config;

/**
 * These config options will only work with dd-java-agent, not with dd-trace-ot.
 *
 * <p>Configure via system properties, environment variables, or config properties file. See online
 * documentation for details.
 *
 * @see {@link TracerConfig} for more tracer config options
 */
public final class TraceInstrumentationConfig {
  public static final String TRACE_ENABLED = "trace.enabled";
  public static final String INTEGRATIONS_ENABLED = "integrations.enabled";

  public static final String TRACE_ANNOTATIONS = "trace.annotations";
  public static final String TRACE_EXECUTORS_ALL = "trace.executors.all";
  public static final String TRACE_EXECUTORS = "trace.executors";
  public static final String TRACE_METHODS = "trace.methods";
  public static final String TRACE_CLASSES_EXCLUDE = "trace.classes.exclude";
  public static final String TRACE_TESTS_ENABLED = "trace.tests.enabled";

  public static final String HTTP_SERVER_TAG_QUERY_STRING = "http.server.tag.query-string";
  public static final String HTTP_CLIENT_TAG_QUERY_STRING = "http.client.tag.query-string";
  public static final String HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN = "trace.http.client.split-by-domain";
  public static final String DB_CLIENT_HOST_SPLIT_BY_INSTANCE = "trace.db.client.split-by-instance";

  public static final String RUNTIME_CONTEXT_FIELD_INJECTION =
      "trace.runtime.context.field.injection";
  public static final String LEGACY_CONTEXT_FIELD_INJECTION =
      "trace.legacy.context.field.injection";
  public static final String SERIALVERSIONUID_FIELD_INJECTION =
      "trace.serialversionuid.field.injection";

  public static final String LOGS_INJECTION_ENABLED = "logs.injection";
  public static final String LOGS_MDC_TAGS_INJECTION_ENABLED = "logs.mdc.tags.injection";

  public static final String KAFKA_CLIENT_PROPAGATION_ENABLED = "kafka.client.propagation.enabled";
  public static final String KAFKA_CLIENT_BASE64_DECODING_ENABLED =
      "kafka.client.base64.decoding.enabled";

  public static final String HYSTRIX_TAGS_ENABLED = "hystrix.tags.enabled";
  public static final String SERVLET_PRINCIPAL_ENABLED = "trace.servlet.principal.enabled";
  public static final String SERVLET_ASYNC_TIMEOUT_ERROR = "trace.servlet.async-timeout.error";

  public static final String TEMP_JARS_CLEAN_ON_BOOT = "temp.jars.clean.on.boot";

  public static final String RESOLVER_USE_LOADCLASS = "resolver.use.loadclass";

  private TraceInstrumentationConfig() {}
}
