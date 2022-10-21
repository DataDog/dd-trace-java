package datadog.trace.api.config;

import java.util.BitSet;

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

  public static final String INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME =
      "integration.synapse.legacy-operation-name";
  public static final String TRACE_ANNOTATIONS = "trace.annotations";
  public static final String TRACE_EXECUTORS_ALL = "trace.executors.all";
  public static final String TRACE_EXECUTORS = "trace.executors";
  public static final String TRACE_METHODS = "trace.methods";
  public static final String TRACE_CLASSES_EXCLUDE = "trace.classes.exclude";
  public static final String TRACE_CLASSES_EXCLUDE_FILE = "trace.classes.exclude.file";
  public static final String TRACE_CLASSLOADERS_EXCLUDE = "trace.classloaders.exclude";
  public static final String TRACE_CODESOURCES_EXCLUDE = "trace.codesources.exclude";
  public static final String TRACE_TESTS_ENABLED = "trace.tests.enabled";

  public static final String TRACE_THREAD_POOL_EXECUTORS_EXCLUDE =
      "trace.thread-pool-executors.exclude";

  public static final String HTTP_SERVER_TAG_QUERY_STRING = "http.server.tag.query-string";
  public static final String HTTP_SERVER_RAW_QUERY_STRING = "http.server.raw.query-string";
  public static final String HTTP_SERVER_RAW_RESOURCE = "http.server.raw.resource";
  public static final String HTTP_SERVER_ROUTE_BASED_NAMING = "http.server.route-based-naming";
  public static final String HTTP_CLIENT_TAG_QUERY_STRING = "http.client.tag.query-string";
  public static final String HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN = "trace.http.client.split-by-domain";
  public static final String DB_CLIENT_HOST_SPLIT_BY_INSTANCE = "trace.db.client.split-by-instance";
  public static final String DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX =
      "trace.db.client.split-by-instance.type.suffix";

  public static final String JDBC_PREPARED_STATEMENT_CLASS_NAME =
      "trace.jdbc.prepared.statement.class.name";

  public static final String JDBC_CONNECTION_CLASS_NAME = "trace.jdbc.connection.class.name";

  public static final String RUNTIME_CONTEXT_FIELD_INJECTION =
      "trace.runtime.context.field.injection";
  public static final String SERIALVERSIONUID_FIELD_INJECTION =
      "trace.serialversionuid.field.injection";

  public static final String LOGS_INJECTION_ENABLED = "logs.injection";
  public static final String LOGS_MDC_TAGS_INJECTION_ENABLED = "logs.mdc.tags.injection";

  public static final String KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS =
      "kafka.client.propagation.disabled.topics";
  public static final String KAFKA_CLIENT_BASE64_DECODING_ENABLED =
      "kafka.client.base64.decoding.enabled";

  public static final String JMS_PROPAGATION_DISABLED_TOPICS = "jms.propagation.disabled.topics";
  public static final String JMS_PROPAGATION_DISABLED_QUEUES = "jms.propagation.disabled.queues";

  public static final String RABBIT_PROPAGATION_DISABLED_QUEUES =
      "rabbit.propagation.disabled.queues";
  public static final String RABBIT_PROPAGATION_DISABLED_EXCHANGES =
      "rabbit.propagation.disabled.exchanges";

  public static final String MESSAGE_BROKER_SPLIT_BY_DESTINATION =
      "message.broker.split-by-destination";

  public static final String GRPC_IGNORED_INBOUND_METHODS = "trace.grpc.ignored.inbound.methods";
  public static final String GRPC_IGNORED_OUTBOUND_METHODS = "trace.grpc.ignored.outbound.methods";
  public static final String GRPC_SERVER_TRIM_PACKAGE_RESOURCE =
      "trace.grpc.server.trim-package-resource";
  public static final String GRPC_SERVER_ERROR_STATUSES = "grpc.server.error.statuses";
  public static final String GRPC_CLIENT_ERROR_STATUSES = "grpc.client.error.statuses";
  public static final String HYSTRIX_TAGS_ENABLED = "hystrix.tags.enabled";
  public static final String HYSTRIX_MEASURED_ENABLED = "hystrix.measured.enabled";

  public static final String IGNITE_CACHE_INCLUDE_KEYS = "ignite.cache.include_keys";

  public static final String OBFUSCATION_QUERY_STRING_REGEXP = "obfuscation.query.string.regexp";

  public static final String PLAY_REPORT_HTTP_STATUS = "trace.play.report-http-status";

  public static final String SERVLET_PRINCIPAL_ENABLED = "trace.servlet.principal.enabled";
  public static final String SERVLET_ASYNC_TIMEOUT_ERROR = "trace.servlet.async-timeout.error";

  public static final String SERVLET_ROOT_CONTEXT_SERVICE_NAME =
      "trace.servlet.root-context.service.name";

  public static final String RESOLVER_OUTLINE_POOL_ENABLED = "resolver.outline.pool.enabled";
  public static final String RESOLVER_OUTLINE_POOL_SIZE = "resolver.outline.pool.size";
  public static final String RESOLVER_TYPE_POOL_SIZE = "resolver.type.pool.size";
  public static final String RESOLVER_USE_LOADCLASS = "resolver.use.loadclass";

  static final boolean DEFAULT_INTEGRATIONS_ENABLED = true;
  static final String DEFAULT_TRACE_ANNOTATIONS = null;
  static final BitSet DEFAULT_GRPC_SERVER_ERROR_STATUSES;
  static final BitSet DEFAULT_GRPC_CLIENT_ERROR_STATUSES;
  static final boolean DEFAULT_LOGS_INJECTION_ENABLED = true;
  static final String DEFAULT_TRACE_METHODS = null;
  static final boolean DEFAULT_TRACE_EXECUTORS_ALL = false;
  static final boolean DEFAULT_HTTP_SERVER_TAG_QUERY_STRING = true;
  static final boolean DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING = true;
  static final boolean DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING = false;
  static final boolean DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN = false;
  static final boolean DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE = false;
  static final boolean DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX = false;
  static final boolean DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION = true;
  static final boolean DEFAULT_SERIALVERSIONUID_FIELD_INJECTION = true;
  static final int DEFAULT_RESOLVER_OUTLINE_POOL_SIZE = 128;
  static final int DEFAULT_RESOLVER_TYPE_POOL_SIZE = 64;

  static {
    DEFAULT_GRPC_SERVER_ERROR_STATUSES = new BitSet();
    DEFAULT_GRPC_SERVER_ERROR_STATUSES.set(2, 17);
    DEFAULT_GRPC_CLIENT_ERROR_STATUSES = new BitSet();
    DEFAULT_GRPC_CLIENT_ERROR_STATUSES.set(1, 17);
  }

  private TraceInstrumentationConfig() {}
}
