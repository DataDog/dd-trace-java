package datadog.trace.api

import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.config.IastConfig
import datadog.trace.api.config.JmxFetchConfig
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.api.config.TracerConfig
import datadog.trace.api.iast.telemetry.Verbosity
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.Strings

class ConfigCollectorTest extends DDSpecification {

  def "non-default config settings get collected"() {
    setup:
    injectEnvConfig(Strings.toEnvVar(configKey), configValue)

    expect:
    ConfigCollector.get().collect().get(configKey) == configValue

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
    TracerConfig.HTTP_CLIENT_ERROR_STATUSES                    | "1:1"
  }

  def "should collect merged data from multiple sources"() {
    setup:
    injectEnvConfig(Strings.toEnvVar(configKey), configValue1)
    injectSysConfig(configKey, configValue2)

    expect:
    ConfigCollector.get().collect().get(configKey) == expectedValue

    where:
    configKey                                                 | configValue1                                   | configValue2              | expectedValue
    // ConfigProvider.getMergedMap
    TracerConfig.TRACE_PEER_SERVICE_MAPPING                   | "service1:best_service,userService:my_service" | "service2:backup_service" | "service2:backup_service,service1:best_service,userService:my_service"
    // ConfigProvider.getOrderedMap
    TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING | "/asdf/*:/test,/b:some"                        | "/a:prop"                 | "/asdf/*:/test,/b:some,/a:prop"
    // ConfigProvider.getMergedMapWithOptionalMappings
    TracerConfig.HEADER_TAGS                                  | "j:ten"                                        | "e:five,b:six"            | "e:five,j:ten,b:six"
  }

  def "default config settings are NOT collected"() {
    expect:
    !ConfigCollector.get().collect().containsKey(configKey)

    where:
    configKey                                                  | _
    IastConfig.IAST_TELEMETRY_VERBOSITY                        | _
    TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA                   | _
    AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING         | _
    GeneralConfig.APPLICATION_KEY                              | _
    TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES         | _
    JmxFetchConfig.JMX_FETCH_CHECK_PERIOD                      | _
    CiVisibilityConfig.CIVISIBILITY_MODULE_ID                  | _
    GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL                 | _
    TracerConfig.TRACE_SAMPLE_RATE                             | _
    TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS | _
    IastConfig.IAST_WEAK_HASH_ALGORITHMS                       | _
    TracerConfig.PROXY_NO_PROXY                                | _
    TracerConfig.TRACE_PEER_SERVICE_MAPPING                    | _
    TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING  | _
    TracerConfig.HEADER_TAGS                                   | _
    TracerConfig.HTTP_CLIENT_ERROR_STATUSES                    | _
  }
}
