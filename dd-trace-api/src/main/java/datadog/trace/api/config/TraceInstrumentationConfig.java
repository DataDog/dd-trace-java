package datadog.trace.api.config;

/**
 * These config options will only work with dd-java-agent, not with dd-trace-ot.
 *
 * <p>Configure via system properties, environment variables, or config properties file. See online
 * documentation for details.
 *
 * @see TracerConfig for more tracer config options
 */
public final class TraceInstrumentationConfig {
  public static final String TRACE_ENABLED = "trace.enabled";
  public static final String TRACE_OTEL_ENABLED = "trace.otel.enabled";
  public static final String INTEGRATIONS_ENABLED = "integrations.enabled";
  public static final String LEGACY_INSTALLER_ENABLED = "legacy.installer.enabled";

  public static final String INTEGRATION_SYNAPSE_LEGACY_OPERATION_NAME =
      "integration.synapse.legacy-operation-name";
  public static final String TRACE_ANNOTATIONS = "trace.annotations";
  public static final String TRACE_ANNOTATION_ASYNC = "trace.annotation.async";
  public static final String TRACE_EXECUTORS_ALL = "trace.executors.all";
  public static final String TRACE_EXECUTORS = "trace.executors";
  public static final String TRACE_METHODS = "trace.methods";
  /*
  format for measure.methods is the same as for trace.methods:
  https://docs.datadoghq.com/tracing/trace_collection/custom_instrumentation/java/
   */
  public static final String MEASURE_METHODS = "measure.methods";
  public static final String TRACE_CLASSES_EXCLUDE = "trace.classes.exclude";
  public static final String TRACE_CLASSES_EXCLUDE_FILE = "trace.classes.exclude.file";
  public static final String TRACE_CLASSLOADERS_EXCLUDE = "trace.classloaders.exclude";
  public static final String TRACE_CODESOURCES_EXCLUDE = "trace.codesources.exclude";

  @SuppressWarnings("unused")
  public static final String TRACE_TESTS_ENABLED = "trace.tests.enabled";

  public static final String TRACE_THREAD_POOL_EXECUTORS_EXCLUDE =
      "trace.thread-pool-executors.exclude";

  public static final String HTTP_SERVER_TAG_QUERY_STRING = "http.server.tag.query-string";
  public static final String HTTP_SERVER_RAW_QUERY_STRING = "http.server.raw.query-string";
  public static final String HTTP_SERVER_RAW_RESOURCE = "http.server.raw.resource";
  public static final String HTTP_SERVER_ROUTE_BASED_NAMING = "http.server.route-based-naming";
  public static final String HTTP_CLIENT_TAG_QUERY_STRING = "http.client.tag.query-string";
  public static final String HTTP_CLIENT_TAG_HEADERS = "http.client.tag.headers";
  public static final String HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN = "trace.http.client.split-by-domain";
  public static final String DB_CLIENT_HOST_SPLIT_BY_INSTANCE = "trace.db.client.split-by-instance";
  public static final String DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX =
      "trace.db.client.split-by-instance.type.suffix";

  public static final String JDBC_PREPARED_STATEMENT_CLASS_NAME =
      "trace.jdbc.prepared.statement.class.name";

  public static final String DB_DBM_PROPAGATION_MODE_MODE = "dbm.propagation.mode";

  public static final String JDBC_CONNECTION_CLASS_NAME = "trace.jdbc.connection.class.name";

  public static final String RUNTIME_CONTEXT_FIELD_INJECTION =
      "trace.runtime.context.field.injection";
  public static final String SERIALVERSIONUID_FIELD_INJECTION =
      "trace.serialversionuid.field.injection";

  public static final String LOGS_INJECTION_ENABLED = "logs.injection";
  public static final String TRACE_128_BIT_TRACEID_LOGGING_ENABLED =
      "trace.128.bit.traceid.logging.enabled";

  public static final String KAFKA_CLIENT_PROPAGATION_DISABLED_TOPICS =
      "kafka.client.propagation.disabled.topics";
  public static final String KAFKA_CLIENT_BASE64_DECODING_ENABLED =
      "kafka.client.base64.decoding.enabled";

  public static final String JMS_PROPAGATION_DISABLED_TOPICS = "jms.propagation.disabled.topics";
  public static final String JMS_PROPAGATION_DISABLED_QUEUES = "jms.propagation.disabled.queues";
  public static final String JMS_UNACKNOWLEDGED_MAX_AGE = "jms.unacknowledged.max.age";

  public static final String RABBIT_PROPAGATION_DISABLED_QUEUES =
      "rabbit.propagation.disabled.queues";
  public static final String RABBIT_PROPAGATION_DISABLED_EXCHANGES =
      "rabbit.propagation.disabled.exchanges";

  public static final String RABBIT_INCLUDE_ROUTINGKEY_IN_RESOURCE =
      "rabbit.include.routingkey.in.resource";

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

  public static final String OBFUSCATION_QUERY_STRING_REGEXP =
      "trace.obfuscation.query.string.regexp";

  public static final String PLAY_REPORT_HTTP_STATUS = "trace.play.report-http-status";

  public static final String SERVLET_PRINCIPAL_ENABLED = "trace.servlet.principal.enabled";
  public static final String SERVLET_ASYNC_TIMEOUT_ERROR = "trace.servlet.async-timeout.error";

  public static final String SERVLET_ROOT_CONTEXT_SERVICE_NAME =
      "trace.servlet.root-context.service.name";

  public static final String SPRING_DATA_REPOSITORY_INTERFACE_RESOURCE_NAME =
      "spring-data.repository.interface.resource-name";

  public static final String RESOLVER_CACHE_CONFIG = "resolver.cache.config";
  public static final String RESOLVER_CACHE_DIR = "resolver.cache.dir";
  public static final String RESOLVER_USE_LOADCLASS = "resolver.use.loadclass";
  public static final String RESOLVER_USE_URL_CACHES = "resolver.use.url.caches";
  public static final String RESOLVER_RESET_INTERVAL = "resolver.reset.interval";
  public static final String RESOLVER_NAMES_ARE_UNIQUE = "resolver.names.are.unique";

  public static final String ELASTICSEARCH_BODY_ENABLED = "trace.elasticsearch.body.enabled";
  public static final String ELASTICSEARCH_PARAMS_ENABLED = "trace.elasticsearch.params.enabled";
  public static final String ELASTICSEARCH_BODY_AND_PARAMS_ENABLED =
      "trace.elasticsearch.body-and-params.enabled";

  public static final String SPARK_TASK_HISTOGRAM_ENABLED = "spark.task-histogram.enabled";

  public static final String JAX_RS_EXCEPTION_AS_ERROR_ENABLED =
      "trace.jax-rs.exception-as-error.enabled";

  private TraceInstrumentationConfig() {}
}
