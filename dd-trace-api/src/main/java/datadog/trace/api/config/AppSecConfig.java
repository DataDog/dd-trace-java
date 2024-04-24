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
  public static final String API_SECURITY_ENABLED = "api-security.enabled";
  public static final String API_SECURITY_ENABLED_EXPERIMENTAL =
      "experimental.api-security.enabled";
  public static final String API_SECURITY_REQUEST_SAMPLE_RATE = "api-security.request.sample.rate";

  public static final String APPSEC_SCA_ENABLED = "appsec.sca.enabled";

  private AppSecConfig() {}
}
