package datadog.trace.api.config

import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification
import spock.lang.Unroll

import static datadog.trace.api.ConfigTest.PREFIX
import static datadog.trace.api.ProductActivationConfig.ENABLED_INACTIVE
import static datadog.trace.api.ProductActivationConfig.FULLY_DISABLED
import static datadog.trace.api.ProductActivationConfig.FULLY_ENABLED
import static datadog.trace.api.config.AppSecConfig.APPSEC_ENABLED
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_HTML
import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_JSON
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP
import static datadog.trace.api.config.AppSecConfig.APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORTING_INBAND
import static datadog.trace.api.config.AppSecConfig.APPSEC_REPORT_TIMEOUT_SEC
import static datadog.trace.api.config.AppSecConfig.APPSEC_RULES_FILE
import static datadog.trace.api.config.AppSecConfig.APPSEC_TRACE_RATE_LIMIT
import static datadog.trace.api.config.AppSecConfig.APPSEC_WAF_METRICS
import static datadog.trace.api.config.AppSecConfig.DEFAULT_APPSEC_REPORTING_INBAND
import static datadog.trace.api.config.AppSecConfig.DEFAULT_APPSEC_TRACE_RATE_LIMIT
import static datadog.trace.api.config.AppSecConfig.DEFAULT_APPSEC_WAF_METRICS

class AppSecConfigTest extends DDSpecification {
  def "check default config values"() {
    when:
    def config = new Config()

    then:
    config.appSecEnabledConfig == ENABLED_INACTIVE
    config.appSecReportingInband == DEFAULT_APPSEC_REPORTING_INBAND
    config.appSecRulesFile == null
    config.appSecReportMinTimeout == 5
    config.appSecReportMaxTimeout == 60
    config.appSecTraceRateLimit == DEFAULT_APPSEC_TRACE_RATE_LIMIT
    config.appSecWafMetrics == DEFAULT_APPSEC_WAF_METRICS
    config.appSecObfuscationParameterKeyRegexp == null
    config.appSecObfuscationParameterValueRegexp == null
    config.appSecHttpBlockedTemplateHtml == null
    config.appSecHttpBlockedTemplateJson == null
  }

  def "check overridden config values"() {
    setup:
    def ruleFile = "/var/file"
    System.setProperty(PREFIX + APPSEC_ENABLED, "true")
    System.setProperty(PREFIX + APPSEC_REPORTING_INBAND, "true")
    System.setProperty(PREFIX + APPSEC_RULES_FILE, ruleFile)
    System.setProperty(PREFIX + APPSEC_REPORT_TIMEOUT_SEC, "80")
    System.setProperty(PREFIX + APPSEC_TRACE_RATE_LIMIT, "120")
    System.setProperty(PREFIX + APPSEC_WAF_METRICS, "false")
    System.setProperty(PREFIX + APPSEC_OBFUSCATION_PARAMETER_KEY_REGEXP, "obfuscation-key-regexp")
    System.setProperty(PREFIX + APPSEC_OBFUSCATION_PARAMETER_VALUE_REGEXP, "obfuscation-value-regexp")
    System.setProperty(PREFIX + APPSEC_HTTP_BLOCKED_TEMPLATE_HTML, "template-html")
    System.setProperty(PREFIX + APPSEC_HTTP_BLOCKED_TEMPLATE_JSON, "template-json")

    when:
    def config = new Config()

    then:
    config.appSecEnabledConfig == FULLY_ENABLED
    config.appSecReportingInband
    config.appSecRulesFile == ruleFile
    config.appSecReportMinTimeout == 5
    config.appSecReportMaxTimeout == 80
    config.appSecTraceRateLimit == 120
    !config.appSecWafMetrics
    config.appSecObfuscationParameterKeyRegexp == "obfuscation-key-regexp"
    config.appSecObfuscationParameterValueRegexp == "obfuscation-value-regexp"
    config.appSecHttpBlockedTemplateHtml == "template-html"
    config.appSecHttpBlockedTemplateJson == "template-json"
  }

  @Unroll
  def "appsec state with sys = #sys env = #env"() {
    setup:
    if (sys != null) {
      System.setProperty("dd.appsec.enabled", sys)
    }
    if (env != null) {
      environmentVariables.set("DD_APPSEC_ENABLED", env)
    }

    when:
    def config = new Config()

    then:
    config.getAppSecEnabledConfig() == res

    where:
    sys        | env        | res
    null       | null       | ENABLED_INACTIVE
    null       | ""         | ENABLED_INACTIVE
    null       | "inactive" | ENABLED_INACTIVE
    null       | "false"    | FULLY_DISABLED
    null       | "0"        | FULLY_DISABLED
    null       | "invalid"  | FULLY_DISABLED
    null       | "true"     | FULLY_ENABLED
    null       | "1"        | FULLY_ENABLED
    ""         | null       | ENABLED_INACTIVE
    ""         | ""         | ENABLED_INACTIVE
    ""         | "inactive" | ENABLED_INACTIVE
    ""         | "false"    | FULLY_DISABLED
    ""         | "0"        | FULLY_DISABLED
    ""         | "invalid"  | FULLY_DISABLED
    ""         | "true"     | FULLY_ENABLED
    ""         | "1"        | FULLY_ENABLED
    "inactive" | null       | ENABLED_INACTIVE
    "inactive" | ""         | ENABLED_INACTIVE
    "inactive" | "inactive" | ENABLED_INACTIVE
    "inactive" | "false"    | ENABLED_INACTIVE
    "inactive" | "0"        | ENABLED_INACTIVE
    "inactive" | "invalid"  | ENABLED_INACTIVE
    "inactive" | "true"     | ENABLED_INACTIVE
    "inactive" | "1"        | ENABLED_INACTIVE
    "false"    | null       | FULLY_DISABLED
    "false"    | ""         | FULLY_DISABLED
    "false"    | "inactive" | FULLY_DISABLED
    "false"    | "false"    | FULLY_DISABLED
    "false"    | "0"        | FULLY_DISABLED
    "false"    | "invalid"  | FULLY_DISABLED
    "false"    | "true"     | FULLY_DISABLED
    "false"    | "1"        | FULLY_DISABLED
    "0"        | null       | FULLY_DISABLED
    "true"     | null       | FULLY_ENABLED
    "true"     | ""         | FULLY_ENABLED
    "true"     | "inactive" | FULLY_ENABLED
    "true"     | "false"    | FULLY_ENABLED
    "true"     | "0"        | FULLY_ENABLED
    "true"     | "invalid"  | FULLY_ENABLED
    "true"     | "true"     | FULLY_ENABLED
    "true"     | "1"        | FULLY_ENABLED
    "1"        | null       | FULLY_ENABLED
  }
}
