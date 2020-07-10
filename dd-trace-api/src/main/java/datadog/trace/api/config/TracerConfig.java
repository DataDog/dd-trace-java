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
public final class TracerConfig {
  public static final String WRITER_TYPE = "writer.type";
  public static final String AGENT_HOST = "agent.host";
  public static final String TRACE_AGENT_PORT = "trace.agent.port";
  public static final String AGENT_PORT_LEGACY = "agent.port";
  public static final String AGENT_UNIX_DOMAIN_SOCKET = "trace.agent.unix.domain.socket";
  public static final String AGENT_TIMEOUT = "trace.agent.timeout";
  public static final String PRIORITY_SAMPLING = "priority.sampling";
  public static final String TRACE_RESOLVER_ENABLED = "trace.resolver.enabled";
  public static final String SERVICE_MAPPING = "service.mapping";

  public static final String SPAN_TAGS = "trace.span.tags";
  public static final String TRACE_ANALYTICS_ENABLED = "trace.analytics.enabled";
  public static final String TRACE_SAMPLING_SERVICE_RULES = "trace.sampling.service.rules";
  public static final String TRACE_SAMPLING_OPERATION_RULES = "trace.sampling.operation.rules";
  public static final String TRACE_SAMPLE_RATE = "trace.sample.rate";
  public static final String TRACE_RATE_LIMIT = "trace.rate.limit";
  public static final String METHOD_TRACE_SAMPLE_RATE = "method.trace.sample.rate";
  /** Temporary config... TODO: remove. */
  public static final String METHOD_TRACE_ENCODE_DATA = "method.trace.encode.data";

  public static final String TRACE_REPORT_HOSTNAME = "trace.report-hostname";
  public static final String HEADER_TAGS = "trace.header.tags";
  public static final String HTTP_SERVER_ERROR_STATUSES = "http.server.error.statuses";
  public static final String HTTP_CLIENT_ERROR_STATUSES = "http.client.error.statuses";

  public static final String SPLIT_BY_TAGS = "trace.split-by-tags";

  public static final String SCOPE_DEPTH_LIMIT = "trace.scope.depth.limit";
  public static final String SCOPE_STRICT_MODE = "trace.scope.strict.mode";
  public static final String PARTIAL_FLUSH_MIN_SPANS = "trace.partial.flush.min.spans";
  public static final String PROPAGATION_STYLE_EXTRACT = "propagation.style.extract";
  public static final String PROPAGATION_STYLE_INJECT = "propagation.style.inject";

  private TracerConfig() {}
}
