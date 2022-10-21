package datadog.trace.api.config;

import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED;
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_HTML;
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_JSON;
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP;
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP;
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORTING_INBAND;
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORT_TIMEOUT_SEC;
import static datadog.trace.api.config.AppSecConfig.APPSEC_RULES_FILE;
import static datadog.trace.api.config.AppSecConfig.APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_METRICS;
import static datadog.trace.api.config.AppSecConfig.DEFAULT_APPSEC_ENABLED;
import static datadog.trace.api.config.AppSecConfig.DEFAULT_APPSEC_REPORTING_INBAND;
import static datadog.trace.api.config.AppSecConfig.DEFAULT_APPSEC_TRACE_RATE_LIMIT;
import static datadog.trace.api.config.AppSecConfig.DEFAULT_APPSEC_WAF_METRICS;

import datadog.trace.api.ProductActivationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.config.provider.SystemPropertiesConfigSource;

public class AppSecFeatureConfig extends AbstractFeatureConfig {
  private final ProductActivationConfig appSecEnabled;
  private final boolean appSecReportingInband;
  private final String appSecRulesFile;
  private final int appSecReportMinTimeout;
  private final int appSecReportMaxTimeout;
  private final int appSecTraceRateLimit;
  private final boolean appSecWafMetrics;
  private final String appSecObfuscationParameterKeyRegexp;
  private final String appSecObfuscationParameterValueRegexp;
  private final String appSecHttpBlockedTemplateHtml;
  private final String appSecHttpBlockedTemplateJson;

  public AppSecFeatureConfig(ConfigProvider configProvider) {
    super(configProvider);
    // ConfigProvider.getString currently doesn't fallback to default for empty strings. So we have
    // special handling here until we have a general solution for empty string value fallback.
    String appSecEnabled = configProvider.getString(APPSEC_ENABLED);
    if (appSecEnabled == null || appSecEnabled.isEmpty()) {
      appSecEnabled =
          configProvider.getStringExcludingSource(
              APPSEC_ENABLED, DEFAULT_APPSEC_ENABLED, SystemPropertiesConfigSource.class);
      if (appSecEnabled.isEmpty()) {
        appSecEnabled = DEFAULT_APPSEC_ENABLED;
      }
    }
    this.appSecEnabled = ProductActivationConfig.fromString(appSecEnabled);
    this.appSecReportingInband =
        configProvider.getBoolean(APPSEC_REPORTING_INBAND, DEFAULT_APPSEC_REPORTING_INBAND);
    this.appSecRulesFile = configProvider.getString(APPSEC_RULES_FILE, null);

    // Default AppSec report timeout min=5, max=60
    this.appSecReportMaxTimeout = configProvider.getInteger(APPSEC_REPORT_TIMEOUT_SEC, 60);
    this.appSecReportMinTimeout = Math.min(this.appSecReportMaxTimeout, 5);

    this.appSecTraceRateLimit =
        configProvider.getInteger(APPSEC_TRACE_RATE_LIMIT, DEFAULT_APPSEC_TRACE_RATE_LIMIT);

    this.appSecWafMetrics =
        configProvider.getBoolean(APPSEC_WAF_METRICS, DEFAULT_APPSEC_WAF_METRICS);

    this.appSecObfuscationParameterKeyRegexp =
        configProvider.getString(APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP, null);
    this.appSecObfuscationParameterValueRegexp =
        configProvider.getString(APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP, null);

    this.appSecHttpBlockedTemplateHtml =
        configProvider.getString(APPSEC_HTTP_BLOCKED_TEMPLATE_HTML, null);
    this.appSecHttpBlockedTemplateJson =
        configProvider.getString(APPSEC_HTTP_BLOCKED_TEMPLATE_JSON, null);
  }

  public ProductActivationConfig getAppSecEnabledConfig() {
    return this.appSecEnabled;
  }

  public boolean isAppSecReportingInband() {
    return this.appSecReportingInband;
  }

  public String getAppSecRulesFile() {
    return this.appSecRulesFile;
  }

  public int getAppSecReportMinTimeout() {
    return this.appSecReportMinTimeout;
  }

  public int getAppSecReportMaxTimeout() {
    return this.appSecReportMaxTimeout;
  }

  public int getAppSecTraceRateLimit() {
    return this.appSecTraceRateLimit;
  }

  public boolean isAppSecWafMetrics() {
    return this.appSecWafMetrics;
  }

  public String getAppSecObfuscationParameterKeyRegexp() {
    return this.appSecObfuscationParameterKeyRegexp;
  }

  public String getAppSecObfuscationParameterValueRegexp() {
    return this.appSecObfuscationParameterValueRegexp;
  }

  public String getAppSecHttpBlockedTemplateHtml() {
    return this.appSecHttpBlockedTemplateHtml;
  }

  public String getAppSecHttpBlockedTemplateJson() {
    return this.appSecHttpBlockedTemplateJson;
  }

  @Override
  public String toString() {
    return "AppSecFeatureConfig{"
        + "appSecEnabled="
        + this.appSecEnabled
        + ", appSecReportingInband="
        + this.appSecReportingInband
        + ", appSecRulesFile='"
        + this.appSecRulesFile
        + '\''
        + ", appSecReportMinTimeout="
        + this.appSecReportMinTimeout
        + ", appSecReportMaxTimeout="
        + this.appSecReportMaxTimeout
        + ", appSecTraceRateLimit="
        + this.appSecTraceRateLimit
        + ", appSecWafMetrics="
        + this.appSecWafMetrics
        + ", appSecObfuscationParameterKeyRegexp='"
        + this.appSecObfuscationParameterKeyRegexp
        + '\''
        + ", appSecObfuscationParameterValueRegexp='"
        + this.appSecObfuscationParameterValueRegexp
        + '\''
        + ", appSecHttpBlockedTemplateHtml='"
        + this.appSecHttpBlockedTemplateHtml
        + '\''
        + ", appSecHttpBlockedTemplateJson='"
        + this.appSecHttpBlockedTemplateJson
        + '\''
        + '}';
  }
}
