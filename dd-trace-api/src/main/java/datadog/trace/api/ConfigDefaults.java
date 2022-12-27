package datadog.trace.api;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;

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
  public static final String DEFAULT_AGENT_WRITER_TYPE = "DDAgentWriter";

  static final String DEFAULT_SITE = "datadoghq.com";

  static final boolean DEFAULT_TRACE_ENABLED = true;
  static final boolean DEFAULT_INTEGRATIONS_ENABLED = true;

  static final boolean DEFAULT_RUNTIME_CONTEXT_FIELD_INJECTION = true;
  static final boolean DEFAULT_SERIALVERSIONUID_FIELD_INJECTION = true;

  static final boolean DEFAULT_PRIORITY_SAMPLING_ENABLED = true;
  static final String DEFAULT_PRIORITY_SAMPLING_FORCE = null;
  static final boolean DEFAULT_TRACE_RESOLVER_ENABLED = true;
  static final boolean DEFAULT_HTTP_SERVER_TAG_QUERY_STRING = true;
  static final boolean DEFAULT_HTTP_SERVER_ROUTE_BASED_NAMING = true;
  static final boolean DEFAULT_HTTP_CLIENT_TAG_QUERY_STRING = false;
  static final boolean DEFAULT_HTTP_CLIENT_SPLIT_BY_DOMAIN = false;
  static final boolean DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE = false;
  static final boolean DEFAULT_DB_CLIENT_HOST_SPLIT_BY_INSTANCE_TYPE_SUFFIX = false;
  static final int DEFAULT_SCOPE_DEPTH_LIMIT = 100;
  static final int DEFAULT_SCOPE_ITERATION_KEEP_ALIVE = 30; // in seconds
  static final int DEFAULT_PARTIAL_FLUSH_MIN_SPANS = 1000;
  static final boolean DEFAULT_PROPAGATION_EXTRACT_LOG_HEADER_NAMES_ENABLED = false;
  static final String DEFAULT_PROPAGATION_STYLE_EXTRACT = PropagationStyle.DATADOG.name();
  static final String DEFAULT_PROPAGATION_STYLE_INJECT = PropagationStyle.DATADOG.name();
  static final boolean DEFAULT_JMX_FETCH_ENABLED = true;
  static final boolean DEFAULT_TRACE_AGENT_V05_ENABLED = false;

  static final boolean DEFAULT_CLIENT_IP_ENABLED = false;

  static final int DEFAULT_CLOCK_SYNC_PERIOD = 30; // seconds

  static final boolean DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_ENABLED = false;
  static final int DEFAULT_JMX_FETCH_MULTIPLE_RUNTIME_SERVICES_LIMIT = 10;

  static final int DEFAULT_DOGSTATSD_START_DELAY = 15; // seconds

  static final boolean DEFAULT_HEALTH_METRICS_ENABLED = true;
  static final boolean DEFAULT_PERF_METRICS_ENABLED = false;
  // No default constants for metrics statsd support -- falls back to jmxfetch values

  static final boolean DEFAULT_LOGS_INJECTION_ENABLED = true;

  static final String DEFAULT_APPSEC_ENABLED = "inactive";
  static final boolean DEFAULT_APPSEC_REPORTING_INBAND = false;
  static final int DEFAULT_APPSEC_TRACE_RATE_LIMIT = 100;
  static final boolean DEFAULT_APPSEC_WAF_METRICS = true;

  static final boolean DEFAULT_IAST_ENABLED = false;
  static final boolean DEFAULT_IAST_DEBUG_ENABLED = false;
  public static final int DEFAULT_IAST_MAX_CONCURRENT_REQUESTS = 2;
  public static final int DEFAULT_IAST_VULNERABILITIES_PER_REQUEST = 2;
  public static final int DEFAULT_IAST_REQUEST_SAMPLING = 30;
  static final Set<String> DEFAULT_IAST_WEAK_HASH_ALGORITHMS =
      new HashSet<>(Arrays.asList("SHA1", "SHA-1", "MD2", "MD5", "RIPEMD128", "MD4"));
  static final String DEFAULT_IAST_WEAK_CIPHER_ALGORITHMS =
      "^(?:PBEWITH(?:HMACSHA(?:2(?:24ANDAES_(?:128|256)|56ANDAES_(?:128|256))|384ANDAES_(?:128|256)|512ANDAES_(?:128|256)|1ANDAES_(?:128|256))|SHA1AND(?:RC(?:2_(?:128|40)|4_(?:128|40))|DESEDE)|MD5AND(?:TRIPLEDES|DES))|DES(?:EDE(?:WRAP)?)?|BLOWFISH|ARCFOUR|RC2).*$";

  static final boolean DEFAULT_IAST_DEDUPLICATION_ENABLED = true;

  static final boolean DEFAULT_CIVISIBILITY_ENABLED = false;
  static final boolean DEFAULT_CIVISIBILITY_AGENTLESS_ENABLED = false;

  static final boolean DEFAULT_REMOTE_CONFIG_ENABLED = true;
  static final boolean DEFAULT_REMOTE_CONFIG_INTEGRITY_CHECK_ENABLED = false;
  static final int DEFAULT_REMOTE_CONFIG_MAX_PAYLOAD_SIZE = 1024; // KiB
  static final int DEFAULT_REMOTE_CONFIG_INITIAL_POLL_INTERVAL = 5; // s
  static final String DEFAULT_REMOTE_CONFIG_TARGETS_KEY_ID =
      "5c4ece41241a1bb513f6e3e5df74ab7d5183dfffbd71bfd43127920d880569fd";
  static final String DEFAULT_REMOTE_CONFIG_TARGETS_KEY =
      "e3f1f98c9da02a93bb547f448b472d727e14b22455235796fe49863856252508";

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

  static final int DEFAULT_RESOLVER_RESET_INTERVAL = 300; // seconds

  static final boolean DEFAULT_TELEMETRY_ENABLED = true;
  static final int DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL = 60; // in seconds

  static final boolean DEFAULT_SECURE_RANDOM = false;

  public static final int DEFAULT_TRACE_X_DATADOG_TAGS_MAX_LENGTH = 512;

  static final boolean DEFAULT_JDBC_SQL_OBFUSCATION = false;

  static final String DEFAULT_LOG_PATTERN="%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger - [%method,%line] %X{dd.service} %X{dd.trace_id} %X{dd.span_id} - %msg%n";
  private ConfigDefaults() {}
}
