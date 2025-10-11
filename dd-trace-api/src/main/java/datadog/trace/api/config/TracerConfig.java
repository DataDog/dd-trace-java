package datadog.trace.api.config;

/**
 * A list of keys to be used in a Properties instance with dd-trace-ot's DDTracer as follows:
 *
 * <pre>
 *   DDTracer.builder().withProperties(new Properties()).build()
 * </pre>
 *
 * <p>If using dd-java-agent, these keys represent settings that should be configured via system
 * properties, environment variables, or config properties file. See online documentation for
 * details.
 *
 * @see TraceInstrumentationConfig for instrumentation specific configuration
 */
public final class TracerConfig {
  public static final String ID_GENERATION_STRATEGY = "id.generation.strategy";
  public static final String WRITER_TYPE = "writer.type";
  public static final String WRITER_BAGGAGE_INJECT = "writer.baggage.inject";

  public static final String PRIORITIZATION_TYPE = "prioritization.type";
  public static final String TRACE_AGENT_URL = "trace.agent.url";
  public static final String AGENT_HOST = "agent.host";
  public static final String TRACE_AGENT_PORT = "trace.agent.port";
  public static final String AGENT_PORT_LEGACY = "agent.port";
  public static final String AGENT_UNIX_DOMAIN_SOCKET = "trace.agent.unix.domain.socket";
  public static final String AGENT_NAMED_PIPE = "trace.pipe.name";
  public static final String AGENT_TIMEOUT = "trace.agent.timeout";
  public static final String FORCE_CLEAR_TEXT_HTTP_FOR_INTAKE_CLIENT =
      "force.clear.text.http.for.intake.client";
  public static final String PROXY_NO_PROXY = "proxy.no_proxy";
  public static final String TRACE_AGENT_PATH = "trace.agent.path";
  public static final String TRACE_AGENT_ARGS = "trace.agent.args";
  public static final String PRIORITY_SAMPLING = "priority.sampling";
  public static final String PRIORITY_SAMPLING_FORCE = "priority.sampling.force";
  @Deprecated public static final String TRACE_RESOLVER_ENABLED = "trace.resolver.enabled";
  public static final String SERVICE_MAPPING = "service.mapping";

  public static final String TRACE_EXPERIMENTAL_FEATURES_ENABLED =
      "trace.experimental.features.enabled";

  public static final String SPAN_TAGS = "trace.span.tags";
  public static final String TRACE_ANALYTICS_ENABLED = "trace.analytics.enabled";

  @Deprecated
  public static final String TRACE_SAMPLING_SERVICE_RULES = "trace.sampling.service.rules";

  @Deprecated
  public static final String TRACE_SAMPLING_OPERATION_RULES = "trace.sampling.operation.rules";

  // JSON rules
  public static final String TRACE_SAMPLING_RULES = "trace.sampling.rules";
  public static final String SPAN_SAMPLING_RULES = "span.sampling.rules";
  public static final String SPAN_SAMPLING_RULES_FILE = "span.sampling.rules.file";
  // a global rate used for all services (that don’t have a dedicated rule defined).
  public static final String TRACE_SAMPLE_RATE = "trace.sample.rate";
  public static final String TRACE_RATE_LIMIT = "trace.rate.limit";
  public static final String TRACE_REPORT_HOSTNAME = "trace.report-hostname";
  public static final String TRACE_CLIENT_IP_HEADER = "trace.client-ip-header";
  public static final String TRACE_CLIENT_IP_RESOLVER_ENABLED = "trace.client-ip.resolver.enabled";
  public static final String TRACE_GIT_METADATA_ENABLED = "trace.git.metadata.enabled";
  public static final String HEADER_TAGS = "trace.header.tags";
  public static final String REQUEST_HEADER_TAGS_COMMA_ALLOWED =
      "trace.request_header.tags.comma.allowed";
  public static final String REQUEST_HEADER_TAGS = "trace.request_header.tags";
  public static final String RESPONSE_HEADER_TAGS = "trace.response_header.tags";
  public static final String BAGGAGE_MAPPING = "trace.header.baggage";
  public static final String TRACE_HTTP_RESOURCE_REMOVE_TRAILING_SLASH =
      "trace.http.resource.remove-trailing-slash";
  public static final String TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING =
      "trace.http.server.path-resource-name-mapping";
  public static final String TRACE_HTTP_CLIENT_PATH_RESOURCE_NAME_MAPPING =
      "trace.http.client.path-resource-name-mapping";
  // Use TRACE_HTTP_SERVER_ERROR_STATUSES instead
  @Deprecated public static final String HTTP_SERVER_ERROR_STATUSES = "http.server.error.statuses";
  public static final String TRACE_HTTP_SERVER_ERROR_STATUSES = "trace.http.server.error.statuses";
  // Use TRACE_HTTP_CLIENT_ERROR_STATUSES instead
  @Deprecated public static final String HTTP_CLIENT_ERROR_STATUSES = "http.client.error.statuses";
  public static final String TRACE_HTTP_CLIENT_ERROR_STATUSES = "trace.http.client.error.statuses";

  public static final String SPLIT_BY_TAGS = "trace.split-by-tags";
  // trace latency interceptor value should be in ms
  public static final String TRACE_KEEP_LATENCY_THRESHOLD_MS =
      "trace.experimental.keep.latency.threshold.ms";
  public static final String SCOPE_DEPTH_LIMIT = "trace.scope.depth.limit";
  public static final String SCOPE_STRICT_MODE = "trace.scope.strict.mode";
  public static final String SCOPE_ITERATION_KEEP_ALIVE = "trace.scope.iteration.keep.alive";
  public static final String PARTIAL_FLUSH_ENABLED = "trace.partial.flush.enabled";
  public static final String PARTIAL_FLUSH_MIN_SPANS = "trace.partial.flush.min.spans";
  public static final String TRACE_STRICT_WRITES_ENABLED = "trace.strict.writes.enabled";
  public static final String PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED =
      "propagation.extract.log_header_names.enabled";
  public static final String PROPAGATION_STYLE_EXTRACT = "propagation.style.extract";
  public static final String PROPAGATION_STYLE_INJECT = "propagation.style.inject";

  public static final String TRACE_PROPAGATION_STYLE = "trace.propagation.style";
  public static final String TRACE_PROPAGATION_STYLE_EXTRACT = "trace.propagation.style.extract";
  public static final String TRACE_PROPAGATION_STYLE_INJECT = "trace.propagation.style.inject";
  public static final String TRACE_PROPAGATION_BEHAVIOR_EXTRACT =
      "trace.propagation.behavior.extract";
  public static final String TRACE_PROPAGATION_EXTRACT_FIRST = "trace.propagation.extract.first";
  public static final String TRACE_BAGGAGE_MAX_ITEMS = "trace.baggage.max.items";
  public static final String TRACE_BAGGAGE_MAX_BYTES = "trace.baggage.max.bytes";
  public static final String TRACE_BAGGAGE_TAG_KEYS = "trace.baggage.tag.keys";

  public static final String TRACE_INFERRED_PROXY_SERVICES_ENABLED =
      "trace.inferred.proxy.services.enabled";

  public static final String ENABLE_TRACE_AGENT_V05 = "trace.agent.v0.5.enabled";

  public static final String CLIENT_IP_ENABLED = "trace.client-ip.enabled";

  public static final String TRACE_128_BIT_TRACEID_GENERATION_ENABLED =
      "trace.128.bit.traceid.generation.enabled";

  public static final String SECURE_RANDOM = "trace.secure-random";

  /**
   * Disables validation that prevents invalid combinations of sampling priority and sampling
   * mechanism on the set sampling priority calls. This check is enabled by default.
   */
  public static final String SAMPLING_MECHANISM_VALIDATION_DISABLED =
      "trace.sampling.mechanism.validation.disabled";

  /**
   * Limit for x-datadog-tags. When exceeded it will stop propagating Datadog tags and will log a
   * warning.
   */
  public static final String TRACE_X_DATADOG_TAGS_MAX_LENGTH = "trace.x-datadog-tags.max.length";

  public static final String CLOCK_SYNC_PERIOD = "trace.clock.sync.period";

  public static final String TRACE_SPAN_ATTRIBUTE_SCHEMA = "trace.span.attribute.schema";

  public static final String TRACE_LONG_RUNNING_ENABLED = "trace.experimental.long-running.enabled";

  public static final String TRACE_LONG_RUNNING_INITIAL_FLUSH_INTERVAL =
      "trace.experimental.long-running.initial.flush.interval";
  public static final String TRACE_LONG_RUNNING_FLUSH_INTERVAL =
      "trace.experimental.long-running.flush.interval";

  public static final String TRACE_PEER_HOSTNAME_ENABLED = "trace.peer.hostname.enabled";

  public static final String TRACE_PEER_SERVICE_DEFAULTS_ENABLED =
      "trace.peer.service.defaults.enabled";
  public static final String TRACE_PEER_SERVICE_COMPONENT_OVERRIDES =
      "trace.peer.service.component.overrides";
  public static final String TRACE_REMOVE_INTEGRATION_SERVICE_NAMES_ENABLED =
      "trace.remove.integration-service-names.enabled";

  public static final String TRACE_PEER_SERVICE_MAPPING = "trace.peer.service.mapping";

  public static final String TRACE_FLUSH_INTERVAL = "trace.flush.interval";

  public static final String TRACE_POST_PROCESSING_TIMEOUT = "trace.post-processing.timeout";

  public static final String TRACE_CLOUD_PAYLOAD_TAGGING_SERVICES =
      "trace.cloud.payload.tagging.services";
  public static final String TRACE_CLOUD_REQUEST_PAYLOAD_TAGGING =
      "trace.cloud.request.payload.tagging";
  public static final String TRACE_CLOUD_RESPONSE_PAYLOAD_TAGGING =
      "trace.cloud.response.payload.tagging";
  public static final String TRACE_CLOUD_PAYLOAD_TAGGING_MAX_DEPTH =
      "trace.cloud.payload.tagging.max-depth";
  public static final String TRACE_CLOUD_PAYLOAD_TAGGING_MAX_TAGS =
      "trace.cloud.payload.tagging.max-tags";

  private TracerConfig() {}
}
