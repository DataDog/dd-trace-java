package datadog.trace.api;

import java.util.BitSet;

public final class ConfigDefaults {

  static final BitSet DEFAULT_HTTP_SERVER_ERROR_STATUSES;
  static final BitSet DEFAULT_HTTP_CLIENT_ERROR_STATUSES;

  static {
    DEFAULT_HTTP_SERVER_ERROR_STATUSES = new BitSet();
    DEFAULT_HTTP_SERVER_ERROR_STATUSES.set(500, 600);
    DEFAULT_HTTP_CLIENT_ERROR_STATUSES = new BitSet();
    DEFAULT_HTTP_CLIENT_ERROR_STATUSES.set(400, 500);
  }

  /* These fields are made public because they're referenced elsewhere internally.  They're not intended as public API. */
  public static final String DEFAULT_AGENT_HOST = "localhost";
  public static final int DEFAULT_TRACE_AGENT_PORT = 8126;
  public static final String DEFAULT_AGENT_UNIX_DOMAIN_SOCKET = null;
  public static final int DEFAULT_AGENT_TIMEOUT = 10; // timeout in seconds
  public static final String DEFAULT_SERVICE_NAME = "unnamed-java-app";

  static final String DEFAULT_SITE = "datadoghq.com";

  static final boolean DEFAULT_TRACE_ENABLED = true;
  static final boolean DEFAULT_INTEGRATIONS_ENABLED = true;
  static final String DEFAULT_AGENT_WRITER_TYPE = "DDAgentWriter";
  static final String DEFAULT_PRIORITIZATION_TYPE = "FastLane";

  static final boolean DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION = true;
  static final boolean DEFAULT_LEGACY_CONTEXT_FIELD_INJECTION = false;
  static final boolean DEFAULT_SERIALVERSIONUID_FIELD_INJECTION = false;

  static final boolean DEFAULT_PRIORITY_SAMPLING_ENABLED = true;
  static final String DEFAULT_PRIORITY_SAMPLING_FORCE = null;
  static final boolean DEFAULT_TRACE_RESOLVER_ENABLED = true;
  static final boolean DEFAULT_HTTP_SERVER_TAG_QUERY_STRING = false;
  static final boolean DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING = false;
  static final boolean DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN = false;
  static final boolean DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE = false;
  static final int DEFAULT_SCOPE_DEPTH_LIMIT = 100;
  static final int DEFAULT_PARTIAL_FLUSH_MIN_SPANS = 1000;
  static final String DEFAULT_PROPAGATION_STYLE_EXTRACT = PropagationStyle.DATADOG.name();
  static final String DEFAULT_PROPAGATION_STYLE_INJECT = PropagationStyle.DATADOG.name();
  static final boolean DEFAULT_JMX_FETCH_ENABLED = true;
  static final boolean DEFAULT_TRACE_AGENT_V05_ENABLED = false;

  static final int DEFAULT_JMX_FETCH_STATSD_PORT = 8125;

  static final boolean DEFAULT_HEALTH_METRICS_ENABLED = true;
  static final boolean DEFAULT_PERF_METRICS_ENABLED = false;
  // No default constants for metrics statsd support -- falls back to jmxfetch values

  static final boolean DEFAULT_LOGS_INJECTION_ENABLED = false;

  static final boolean DEFAULT_PROFILING_ENABLED = false;
  static final int DEFAULT_PROFILING_START_DELAY = 10;
  static final boolean DEFAULT_PROFILING_START_FORCE_FIRST = false;
  static final int DEFAULT_PROFILING_UPLOAD_PERIOD = 60; // 1 min
  static final int DEFAULT_PROFILING_UPLOAD_TIMEOUT = 30; // seconds
  static final String DEFAULT_PROFILING_UPLOAD_COMPRESSION = "on";
  static final int DEFAULT_PROFILING_PROXY_PORT = 8080;
  static final int DEFAULT_PROFILING_EXCEPTION_SAMPLE_LIMIT = 10_000;
  static final int DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_TOP_ITEMS = 50;
  static final int DEFAULT_PROFILING_EXCEPTION_HISTOGRAM_MAX_COLLECTION_SIZE = 10000;

  static final boolean DEFAULT_KAFKA_CLIENT_PROPAGATION_ENABLED = true;

  static final boolean DEFAULT_TRACE_REPORT_HOSTNAME = false;
  static final String DEFAULT_TRACE_ANNOTATIONS = null;
  static final boolean DEFAULT_TRACE_EXECUTORS_ALL = false;
  static final String DEFAULT_TRACE_METHODS = null;
  static final boolean DEFAULT_TRACE_ANALYTICS_ENABLED = false;
  static final float DEFAULT_ANALYTICS_SAMPLE_RATE = 1.0f;
  static final int DEFAULT_TRACE_RATE_LIMIT = 100;

  public static final boolean DEFAULT_ASYNC_PROPAGATING = true;

  private ConfigDefaults() {}
}
