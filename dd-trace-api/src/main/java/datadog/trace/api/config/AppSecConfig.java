package datadog.trace.api.config;

/** Constant with names of configuration options for appsec. */
public final class AppSecConfig {

  public static final String APPSEC_ENABLED = "appsec.enabled";
  public static final String APPSEC_REPORTING_INBAND = "appsec.reporting.inband";
  public static final String APPSEC_RULES_FILE = "appsec.rules";
  public static final String APPSEC_REPORT_TIMEOUT_SEC = "appsec.report.timeout";
  public static final String APPSEC_IP_ADDR_HEADER = "appsec.ipheader";
  public static final String APPSEC_TRACE_RATE_LIMIT = "appsec.trace.rate.limit";
  public static final String APPSEC_WAF_METRICS = "appsec.waf.metrics";
  public static final String APPSEC_WAF_TIMEOUT = "appsec.waf.timeout";
  public static final String APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP =
      "appsec.obfuscation.parameter_key_regexp";
  public static final String APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP =
      "appsec.obfuscation.parameter_value_regexp";
  public static final String APPSEC_HTTP_BLOCKED_TEMPLATE_HTML =
      "appsec.http.blocked.template.html";
  public static final String APPSEC_HTTP_BLOCKED_TEMPLATE_JSON =
      "appsec.http.blocked.template.json";
  public static final String APPSEC_AUTOMATED_USER_EVENTS_TRACKING =
      "appsec.automated-user-events-tracking";
  public static final String APPSEC_AUTO_USER_INSTRUMENTATION_MODE =
      "appsec.auto-user-instrumentation-mode";
  public static final String APPSEC_BODY_PARSING_SIZE_LIMIT = "appsec.body-parsing-size-limit";
  public static final String API_SECURITY_ENABLED = "api-security.enabled";
  public static final String API_SECURITY_ENABLED_EXPERIMENTAL =
      "experimental.api-security.enabled";
  public static final String API_SECURITY_SAMPLE_DELAY = "api-security.sample.delay";
  public static final String API_SECURITY_ENDPOINT_COLLECTION_ENABLED =
      "api-security.endpoint.collection.enabled";
  public static final String API_SECURITY_ENDPOINT_COLLECTION_MESSAGE_LIMIT =
      "api-security.endpoint.collection.message.limit";
  public static final String API_SECURITY_DOWNSTREAM_REQUEST_ANALYSIS_SAMPLE_RATE =
      "api-security.downstream.request.analysis.sample_rate";
  public static final String API_SECURITY_DOWNSTREAM_REQUEST_BODY_ANALYSIS_SAMPLE_RATE =
      "api-security.downstream.request.body.analysis.sample_rate";
  public static final String API_SECURITY_MAX_DOWNSTREAM_REQUEST_BODY_ANALYSIS =
      "api-security.max.downstream.request.body.analysis";

  public static final String APPSEC_SCA_ENABLED = "appsec.sca.enabled";
  public static final String APPSEC_RASP_ENABLED = "appsec.rasp.enabled";
  public static final String APPSEC_STACK_TRACE_ENABLED = "appsec.stack-trace.enabled";
  public static final String APPSEC_STACKTRACE_ENABLED_DEPRECATED =
      "appsec.stacktrace.enabled"; // old non-standard as a fallback alias
  public static final String APPSEC_MAX_STACK_TRACES = "appsec.max.stack-traces";
  public static final String APPSEC_MAX_STACKTRACES_DEPRECATED =
      "appsec.max.stacktraces"; // old non-standard as a fallback alias
  public static final String APPSEC_MAX_STACK_TRACE_DEPTH = "appsec.max.stack-trace.depth";
  public static final String APPSEC_MAX_STACKTRACE_DEPTH_DEPRECATED =
      "appsec.max.stacktrace.depth"; // old non-standard as a fallback alias

  private AppSecConfig() {}
}
