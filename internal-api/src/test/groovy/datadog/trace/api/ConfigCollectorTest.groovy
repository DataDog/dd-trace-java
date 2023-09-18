package datadog.trace.api

import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.IastConfig
import datadog.trace.api.config.JmxFetchConfig
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.telemetry.Verbosity
import datadog.trace.api.naming.SpanNaming
import datadog.trace.bootstrap.config.provider.ConfigConverter
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.Strings

import static datadog.trace.api.ConfigDefaults.DEFAULT_HTTP_CLIENT_ERROR_STATUSES
import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_HASH_ALGORITHMS
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL
import static datadog.trace.api.UserEventTrackingMode.SAFE

class ConfigCollectorTest extends DDSpecification {

  def "non-default config settings get collected"() {
    setup:
    injectEnvConfig(Strings.toEnvVar(configKey), configValue)

    expect:
    def setting = ConfigCollector.get().collect().get(configKey)
    setting.value == configValue
    setting.origin == ConfigOrigin.ENV

    where:
    configKey                                                  | configValue
    // ConfigProvider.getEnum
    IastConfig.IAST_TELEMETRY_VERBOSITY                        | Verbosity.DEBUG.toString()
    // ConfigProvider.getString
    TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA                   | "v1"
    // ConfigProvider.getStringNotEmpty
    AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING         | UserEventTrackingMode.EXTENDED.toString()
    // ConfigProvider.getStringExcludingSource
    GeneralConfig.APPLICATION_KEY                              | "app-key"
    // ConfigProvider.getBoolean
    TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES         | "true"
    // ConfigProvider.getInteger
    JmxFetchConfig.JMX_FETCH_CHECK_PERIOD                      | "60"
    // ConfigProvider.getLong
    CiVisibilityConfig.CIVISIBILITY_MODULE_ID                  | "450273"
    // ConfigProvider.getFloat
    GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL                 | "1.5"
    // ConfigProvider.getDouble
    TracerConfig.TRACE_SAMPLE_RATE                             | "2.2"
    // ConfigProvider.getList
    TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS | "someTopic,otherTopic"
    // ConfigProvider.getSet
    IastConfig.IAST_WEAK_HASH_ALGORITHMS                       | "SHA1,SHA-1"
    // ConfigProvider.getSpacedList
    TracerConfig.PROXY_NO_PROXY                                | "a b c"
    // ConfigProvider.getMergedMap
    TracerConfig.TRACE_PEER_SERVICE_MAPPING                    | "service1:best_service,userService:my_service"
    // ConfigProvider.getOrderedMap
    TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING  | "/asdf/*:/test"
    // ConfigProvider.getMergedMapWithOptionalMappings
    TracerConfig.HEADER_TAGS                                   | "e:five"
    // ConfigProvider.getIntegerRange
    TracerConfig.HTTP_CLIENT_ERROR_STATUSES                    | "400-402"
  }

  def "should collect merged data from multiple sources"() {
    setup:
    injectEnvConfig(Strings.toEnvVar(configKey), configValue1)
    injectSysConfig(configKey, configValue2)

    expect:
    def setting = ConfigCollector.get().collect().get(configKey)
    setting.value == expectedValue
    setting.origin == ConfigOrigin.JVM_PROP

    where:
    configKey                                                 | configValue1                                   | configValue2              | expectedValue
    // ConfigProvider.getMergedMap
    TracerConfig.TRACE_PEER_SERVICE_MAPPING                   | "service1:best_service,userService:my_service" | "service2:backup_service" | "service2:backup_service,service1:best_service,userService:my_service"
    // ConfigProvider.getOrderedMap
    TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING | "/asdf/*:/test,/b:some"                        | "/a:prop"                 | "/asdf/*:/test,/b:some,/a:prop"
    // ConfigProvider.getMergedMapWithOptionalMappings
    TracerConfig.HEADER_TAGS                                  | "j:ten"                                        | "e:five,b:six"            | "e:five,j:ten,b:six"
  }

  def "default config settings are collected"() {
    expect:
    def setting = ConfigCollector.get().collect().get(configKey)
    setting.origin == ConfigOrigin.DEFAULT
    setting.value == defaultValue

    where:
    configKey                                                  | defaultValue
    IastConfig.IAST_TELEMETRY_VERBOSITY                        | Verbosity.INFORMATION.toString()
    TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA                   | "v" + SpanNaming.SCHEMA_MIN_VERSION
    AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING         | SAFE.toString()
    GeneralConfig.APPLICATION_KEY                              | null
    TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES         | null
    JmxFetchConfig.JMX_FETCH_CHECK_PERIOD                      | null
    CiVisibilityConfig.CIVISIBILITY_MODULE_ID                  | null
    GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL                 | DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL
    TracerConfig.TRACE_SAMPLE_RATE                             | null
    TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS | null
    IastConfig.IAST_WEAK_HASH_ALGORITHMS                       | DEFAULT_IAST_WEAK_HASH_ALGORITHMS.join(",")
    TracerConfig.PROXY_NO_PROXY                                | null
    TracerConfig.TRACE_PEER_SERVICE_MAPPING                    | ""
    TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING  | ""
    TracerConfig.HEADER_TAGS                                   | ""
    TracerConfig.HTTP_CLIENT_ERROR_STATUSES                    | ConfigConverter.renderIntegerRange(DEFAULT_HTTP_CLIENT_ERROR_STATUSES)
  }
}
