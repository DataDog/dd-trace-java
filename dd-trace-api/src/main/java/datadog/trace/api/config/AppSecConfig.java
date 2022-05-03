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
  public static final String APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP =
      "appsec.obfuscation.parameter_key_regexp";
  public static final String APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP =
      "appsec.obfuscation.parameter_value_regexp";
  public static final String APPSEC_TELEMETRY = "appsec.telemetry";
  public static final String APPSEC_DEPENDENCIES = "appsec.dependencies";

  private AppSecConfig() {}
}
