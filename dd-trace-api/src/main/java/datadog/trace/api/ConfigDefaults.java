package datadog.trace.api;

import java.util.BitSet;

public final class ConfigDefaults {

  static final BitSet DEFAULT_HTTP_SERVER_ERROR_STATUSES;
  static final BitSet DEFAULT_HTTP_CLIENT_ERROR_STATUSES;
  static final BitSet DEFAULT_GRPC_SERVER_ERROR_STATUSES;
  static final BitSet DEFAULT_GRPC_CLIENT_ERROR_STATUSES;

  static {
    DEFAULT_HTTP_SERVER_ERROR_STATUSES = new BitSet();
    DEFAULT_HTTP_SERVER_ERROR_STATUSES.set(500, 600);
    DEFAULT_HTTP_CLIENT_ERROR_STATUSES = new BitSet();
    DEFAULT_HTTP_CLIENT_ERROR_STATUSES.set(400, 500);
    DEFAULT_GRPC_SERVER_ERROR_STATUSES = new BitSet();
    DEFAULT_GRPC_SERVER_ERROR_STATUSES.set(2, 17);
    DEFAULT_GRPC_CLIENT_ERROR_STATUSES = new BitSet();
    DEFAULT_GRPC_CLIENT_ERROR_STATUSES.set(1, 17);
  }

  /* These fields are made public because they're referenced elsewhere internally.  They're not intended as public API. */
  public static final String DEFAULT_AGENT_HOST = "localhost";
  public static final int DEFAULT_TRACE_AGENT_PORT = 8126;
  public static final int DEFAULT_DOGSTATSD_PORT = 8125;
  public static final String DEFAULT_TRACE_AGENT_SOCKET_PATH = "/var/run/datadog/apm.socket";
  public static final String DEFAULT_DOGSTATSD_SOCKET_PATH = "/var/run/datadog/dsd.socket";
  public static final int DEFAULT_AGENT_TIMEOUT = 10; // timeout in seconds
  public static final String DEFAULT_SERVICE_NAME = "unnamed-java-app";
  public static final String DEFAULT_SERVLET_ROOT_CONTEXT_SERVICE_NAME = "root-servlet";

  static final String DEFAULT_SITE = "datadoghq.com";

  static final boolean DEFAULT_TRACE_ENABLED = true;
  static final boolean DEFAULT_INTEGRATIONS_ENABLED = true;
  static final String DEFAULT_AGENT_WRITER_TYPE = "DDAgentWriter";

  static final boolean DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION = true;
  static final boolean DEFAULT_SERIALVERSIONUID_FIELD_INJECTION = true;

  static final boolean DEFAULT_PRIORITY_SAMPLING_ENABLED = true;
  static final String DEFAULT_PRIORITY_SAMPLING_FORCE = null;
  static final boolean DEFAULT_TRACE_RESOLVER_ENABLED = true;
  static final boolean DEFAULT_HTTP_SERVER_TAG_QUERY_STRING = false;
  static final boolean DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING = true;
  static final boolean DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING = false;
  static final boolean DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN = false;
  static final boolean DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE = false;
  static final boolean DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX = false;
  static final int DEFAULT_SCOPE_DEPTH_LIMIT = 100;
  static final int DEFAULT_SCOPE_ITERATION_KEEP_ALIVE = 10; // in seconds
  static final int DEFAULT_PARTIAL_FLUSH_MIN_SPANS = 1000;
  static final boolean DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED = false;
  static final String DEFAULT_PROPAGATION_STYLE_EXTRACT = PropagationStyle.DATADOG.name();
  static final String DEFAULT_PROPAGATION_STYLE_INJECT = PropagationStyle.DATADOG.name();
  static final boolean DEFAULT_JMX_FETCH_ENABLED = true;
  static final boolean DEFAULT_TRACE_AGENT_V05_ENABLED = false;

  static final int DEFAULT_CLOCK_SYNC_PERIOD = 30; // seconds

  static final boolean DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED = false;
  static final int DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT = 10;

  static final int DEFAULT_DOGSTATSD_START_DELAY = 15; // seconds

  static final boolean DEFAULT_HEALTH_METRICS_ENABLED = true;
  static final boolean DEFAULT_PERF_METRICS_ENABLED = false;
  // No default constants for metrics statsd support -- falls back to jmxfetch values

  static final boolean DEFAULT_LOGS_INJECTION_ENABLED = true;

  static final boolean DEFAULT_APPSEC_ENABLED = false;
  static final boolean DEFAULT_APPSEC_REPORTING_INBAND = false;
  static final int DEFAULT_APPSEC_TRACE_RATE_LIMIT = 100;
  static final boolean DEFAULT_APPSEC_WAF_METRICS = true;

  static final boolean DEFAULT_CIVISIBILITY_ENABLED = false;
  static final boolean DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED = false;

  static final boolean DEFAULT_DEBUGGER_ENABLED = false;
  static final int DEFAULT_DEBUGGER_UPLOAD_TIMEOUT = 30; // seconds
  static final int DEFAULT_DEBUGGER_UPLOAD_FLUSH_INTERVAL = 0; // ms, 0 = dynamic
  static final boolean DEFAULT_DEBUGGER_CLASSFILE_DUMP_ENABLED = false;
  static final int DEFAULT_DEBUGGER_POLL_INTERVAL = 1; // seconds
  static final int DEFAULT_DEBUGGER_DIAGNOSTICS_INTERVAL = 60 * 60; // seconds
  static final boolean DEFAULT_DEBUGGER_METRICS_ENABLED = true;
  static final int DEFAULT_DEBUGGER_UPLOAD_BATCH_SIZE = 100;
  static final int DEFAULT_DEBUGGER_MAX_PAYLOAD_SIZE = 1024; // KiB
  static final boolean DEFAULT_DEBUGGER_VERIFY_BYTECODE = false;
  static final boolean DEFAULT_DEBUGGER_INSTRUMENT_THE_WORLD = false;

  static final boolean DEFAULT_TRACE_REPORT_HOSTNAME = false;
  static final String DEFAULT_TRACE_ANNOTATIONS = null;
  static final boolean DEFAULT_TRACE_EXECUTORS_ALL = false;
  static final String DEFAULT_TRACE_METHODS = null;
  static final boolean DEFAULT_TRACE_ANALYTICS_ENABLED = false;
  static final float DEFAULT_ANALYTICS_SAMPLE_RATE = 1.0f;
  static final int DEFAULT_TRACE_RATE_LIMIT = 100;

  public static final boolean DEFAULT_ASYNC_PROPAGATING = true;

  static final boolean DEFAULT_CWS_ENABLED = false;
  static final int DEFAULT_CWS_TLS_REFRESH = 5000;

  static final boolean DEFAULT_DATA_STREAMS_ENABLED = false;

  static final int DEFAULT_RESOLVER_OUTLINE_POOL_SIZE = 128;
  static final int DEFAULT_RESOLVER_TYPE_POOL_SIZE = 64;

  private ConfigDefaults() {}
}
