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
  public static final String APPLICATION_KEY = "application-key";
  public static final String APP_KEY = "app-key"; // alias for application key
  public static final String API_KEY_FILE = "api-key-file";
  public static final String APPLICATION_KEY_FILE = "application-key-file";
  public static final String SITE = "site";

  public static final String SERVICE_NAME = "service.name";
  public static final String SERVICE_NAME_SET_BY_USER = "service.name.set.by.user";
  public static final String ENV = "env";
  public static final String VERSION = "version";
  public static final String PRIMARY_TAG = "primary.tag";
  public static final String TRACE_TAGS = "trace.tags";
  public static final String TAGS = "tags";
  @Deprecated // Use dd.tags instead
  public static final String GLOBAL_TAGS = "trace.global.tags";

  public static final String EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED =
      "experimental.propagate.process.tags.enabled";

  public static final String LOG_LEVEL = "log.level";
  public static final String TRACE_LOG_LEVEL = "trace.log.level";
  public static final String TRACE_DEBUG = "trace.debug";
  public static final String TRACE_TRIAGE = "trace.triage";
  public static final String TRIAGE_REPORT_TRIGGER = "triage.report.trigger";
  public static final String TRIAGE_REPORT_DIR = "triage.report.dir";

  public static final String STARTUP_LOGS_ENABLED = "trace.startup.logs";

  public static final String DOGSTATSD_START_DELAY = "dogstatsd.start-delay";
  public static final String DOGSTATSD_HOST = "dogstatsd.host";
  public static final String DOGSTATSD_PORT = "dogstatsd.port";
  public static final String DOGSTATSD_PATH = "dogstatsd.path";
  public static final String DOGSTATSD_ARGS = "dogstatsd.args";
  public static final String DOGSTATSD_NAMED_PIPE = "dogstatsd.pipe.name";

  public static final String STATSD_CLIENT_QUEUE_SIZE = "statsd.client.queue.size";
  public static final String STATSD_CLIENT_SOCKET_BUFFER = "statsd.client.socket.buffer";
  public static final String STATSD_CLIENT_SOCKET_TIMEOUT = "statsd.client.socket.timeout";

  public static final String RUNTIME_METRICS_ENABLED = "runtime.metrics.enabled";
  public static final String RUNTIME_ID_ENABLED = "runtime-id.enabled";
  public static final String RUNTIME_METRICS_RUNTIME_ID_ENABLED =
      "runtime.metrics.runtime-id.enabled";

  public static final String HEALTH_METRICS_ENABLED = "trace.health.metrics.enabled";
  public static final String HEALTH_METRICS_STATSD_HOST = "trace.health.metrics.statsd.host";
  public static final String HEALTH_METRICS_STATSD_PORT = "trace.health.metrics.statsd.port";
  public static final String PERF_METRICS_ENABLED = "trace.perf.metrics.enabled";

  public static final String TRACE_STATS_COMPUTATION_ENABLED = "trace.stats.computation.enabled";
  public static final String TRACER_METRICS_ENABLED = "trace.tracer.metrics.enabled";
  public static final String TRACER_METRICS_BUFFERING_ENABLED =
      "trace.tracer.metrics.buffering.enabled";
  public static final String TRACER_METRICS_MAX_AGGREGATES = "trace.tracer.metrics.max.aggregates";
  public static final String TRACER_METRICS_MAX_PENDING = "trace.tracer.metrics.max.pending";
  public static final String TRACER_METRICS_IGNORED_RESOURCES =
      "trace.tracer.metrics.ignored.resources";

  public static final String AZURE_APP_SERVICES = "azure.app.services";
  public static final String INTERNAL_EXIT_ON_FAILURE = "trace.internal.exit.on.failure";

  public static final String DATA_JOBS_ENABLED = "data.jobs.enabled";
  public static final String DATA_JOBS_COMMAND_PATTERN = "data.jobs.command.pattern";
  public static final String DATA_JOBS_OPENLINEAGE_ENABLED = "data.jobs.openlineage.enabled";
  public static final String DATA_JOBS_OPENLINEAGE_TIMEOUT_ENABLED =
      "data.jobs.openlineage.timeout.enabled";
  public static final String DATA_JOBS_PARSE_SPARK_PLAN_ENABLED =
      "data.jobs.parse_spark_plan.enabled";
  public static final String DATA_JOBS_EXPERIMENTAL_FEATURES_ENABLED =
      "data.jobs.experimental_features.enabled";

  public static final String DATA_STREAMS_ENABLED = "data.streams.enabled";
  public static final String DATA_STREAMS_BUCKET_DURATION_SECONDS =
      "data.streams.bucket_duration.seconds";
  public static final String DATA_STREAMS_TRANSACTION_EXTRACTORS =
      "data.streams.transaction_extractors";

  public static final String TELEMETRY_ENABLED = "instrumentation.telemetry.enabled";
  public static final String TELEMETRY_HEARTBEAT_INTERVAL = "telemetry.heartbeat.interval";
  public static final String TELEMETRY_EXTENDED_HEARTBEAT_INTERVAL =
      "telemetry.extended.heartbeat.interval";
  public static final String TELEMETRY_METRICS_INTERVAL = "telemetry.metrics.interval";
  public static final String TELEMETRY_METRICS_ENABLED = "telemetry.metrics.enabled";
  public static final String TELEMETRY_DEPENDENCY_COLLECTION_ENABLED =
      "telemetry.dependency-collection.enabled";
  public static final String TELEMETRY_LOG_COLLECTION_ENABLED = "telemetry.log-collection.enabled";
  public static final String TELEMETRY_DEPENDENCY_RESOLUTION_QUEUE_SIZE =
      "telemetry.dependency-resolution.queue.size";
  public static final String TELEMETRY_DEBUG_REQUESTS_ENABLED = "telemetry.debug.requests.enabled";
  public static final String AGENTLESS_LOG_SUBMISSION_ENABLED = "agentless.log.submission.enabled";
  public static final String AGENTLESS_LOG_SUBMISSION_QUEUE_SIZE =
      "agentless.log.submission.queue.size";
  public static final String TELEMETRY_DEPENDENCY_RESOLUTION_PERIOD_MILLIS =
      "telemetry.dependency.resolution.period.millis";
  public static final String AGENTLESS_LOG_SUBMISSION_LEVEL = "agentless.log.submission.level";
  public static final String AGENTLESS_LOG_SUBMISSION_URL = "agentless.log.submission.url";
  public static final String APM_TRACING_ENABLED = "apm.tracing.enabled";
  public static final String JDK_SOCKET_ENABLED = "jdk.socket.enabled";

  public static final String OPTIMIZED_MAP_ENABLED = "optimized.map.enabled";
  public static final String TAG_NAME_UTF8_CACHE_SIZE = "tag.name.utf8.cache.size";
  public static final String TAG_VALUE_UTF8_CACHE_SIZE = "tag.value.utf8.cache.size";
  public static final String SPAN_BUILDER_REUSE_ENABLED = "span.builder.reuse.enabled";
  public static final String STACK_TRACE_LENGTH_LIMIT = "stack.trace.length.limit";

  public static final String SSI_INJECTION_ENABLED = "injection.enabled";
  public static final String SSI_INJECTION_FORCE = "inject.force";
  public static final String INSTRUMENTATION_SOURCE = "instrumentation.source";
  public static final String APP_LOGS_COLLECTION_ENABLED = "app.logs.collection.enabled";

  public static final String HTTP_CLIENT_IMPLEMENTATION = "http.client.implementation";

  private GeneralConfig() {}
}
