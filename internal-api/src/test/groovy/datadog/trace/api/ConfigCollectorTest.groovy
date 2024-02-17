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
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.Strings

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

  def "default not-null config settings are collected"() {
    expect:
    def setting = ConfigCollector.get().collect().get(configKey)
    setting.origin == ConfigOrigin.DEFAULT
    setting.value == defaultValue

    where:
    configKey                                                  | defaultValue
    IastConfig.IAST_TELEMETRY_VERBOSITY                        | Verbosity.INFORMATION.toString()
    TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA                   | "v" + SpanNaming.SCHEMA_MIN_VERSION
    AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING         | SAFE.toString()
    GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL                 | DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL
    CiVisibilityConfig.CIVISIBILITY_JACOCO_GRADLE_SOURCE_SETS  | "main"
    IastConfig.IAST_WEAK_HASH_ALGORITHMS                       | DEFAULT_IAST_WEAK_HASH_ALGORITHMS.join(",")
    TracerConfig.HTTP_CLIENT_ERROR_STATUSES                    | "400-500"
  }

  def "default null config settings are also collected"() {
    when:
    ConfigSetting cs = ConfigCollector.get().collect().get(configKey)

    then:
    cs.key == configKey
    cs.value == null
    cs.origin == ConfigOrigin.DEFAULT

    where:
    configKey << [
      GeneralConfig.APPLICATION_KEY,
      TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES,
      JmxFetchConfig.JMX_FETCH_CHECK_PERIOD,
      CiVisibilityConfig.CIVISIBILITY_MODULE_ID,
      TracerConfig.TRACE_SAMPLE_RATE,
      TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS,
      TracerConfig.PROXY_NO_PROXY,
      TracerConfig.TRACE_PEER_SERVICE_MAPPING,
      TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING,
      TracerConfig.HEADER_TAGS,
    ]
  }

  def "put-get configurations"() {
    setup:
    ConfigCollector.get().collect()

    when:
    ConfigCollector.get().put('key1', 'value1', ConfigOrigin.DEFAULT)
    ConfigCollector.get().put('key2', 'value2', ConfigOrigin.ENV)
    ConfigCollector.get().put('key1', 'replaced', ConfigOrigin.REMOTE)
    ConfigCollector.get().put('key3', 'value3', ConfigOrigin.JVM_PROP)

    then:
    ConfigCollector.get().collect().values().toSet() == [
      ConfigSetting.of('key1', 'replaced', ConfigOrigin.REMOTE),
      ConfigSetting.of('key2', 'value2', ConfigOrigin.ENV),
      ConfigSetting.of('key3', 'value3', ConfigOrigin.JVM_PROP)
    ] as Set
  }


  def "hide pii configuration data"() {
    setup:
    ConfigCollector.get().collect()

    when:
    ConfigCollector.get().put('DD_API_KEY', 'sensitive data', ConfigOrigin.ENV)

    then:
    ConfigCollector.get().collect().get('DD_API_KEY').value == '<hidden>'
  }

  def "collects common setting default values"() {
    when:
    def settings = ConfigCollector.get().collect()

    then:
    def setting = settings.get(key)

    setting.key == key
    setting.value == value
    setting.origin == ConfigOrigin.DEFAULT

    where:
    key                      | value
    "trace.enabled"          | true
    "profiling.enabled"      | false
    "appsec.enabled"         | "inactive" //TODO false
    "data.streams.enabled"   | false
    "trace.tags"             | null // TODO ""
    "trace.header.tags"      | null // TODO ""
    "logs.injection.enabled" | true // TODO false
    "trace.sample.rate"      | null // TODO 1.0
  }

  def "collects common setting overridden values"() {
    setup:
    injectEnvConfig("DD_TRACE_ENABLED", "false")
    injectEnvConfig("DD_PROFILING_ENABLED", "true")
    injectEnvConfig("DD_APPSEC_ENABLED", "false")
    injectEnvConfig("DD_DATA_STREAMS_ENABLED", "true")
    injectEnvConfig("DD_TAGS", "team:apm,component:web")
    injectEnvConfig("DD_TRACE_HEADER_TAGS", "X-Header-Tag-1:header_tag_1,X-Header-Tag-2:header_tag_2")
    injectEnvConfig("DD_LOGS_INJECTION", "false")
    injectEnvConfig("DD_TRACE_SAMPLE_RATE", "0.3")

    when:
    def settings = ConfigCollector.get().collect()

    then:
    def setting = settings.get(key)

    setting.key == key
    setting.value == value
    setting.origin == ConfigOrigin.ENV

    where:
    key                      | value
    "trace.enabled"          | "false"
    "profiling.enabled"      | "true"
    "appsec.enabled"         | "false"
    "data.streams.enabled"   | "true"
    "trace.tags"             | "component:web,team:apm" // TODO should it preserve ordering? "team:apm,component:web"
    "trace.header.tags"      | "X-Header-Tag-1:header_tag_1,X-Header-Tag-2:header_tag_2".toLowerCase() // TODO should it preserve case?
    "logs.injection.enabled" | "false"
    "trace.sample.rate"      | "0.3"
  }
}
