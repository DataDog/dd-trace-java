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

class ConfigCollectorTest extends DDSpecification {

  def "non-default config settings get collected"() {
    setup:
    injectEnvConfig(Strings.toEnvVar(configKey), configValue)

    expect:
    def setting = ConfigCollector.get().collect().get(configKey)
    setting.stringValue() == configValue
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
    CiVisibilityConfig.CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS | "450273"
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
    TracerConfig.TRACE_HTTP_CLIENT_ERROR_STATUSES              | "400-402"
  }

  def "should collect merged data from multiple sources"() {
    setup:
    injectEnvConfig(Strings.toEnvVar(configKey), envValue)
    if (jvmValue != null) {
      injectSysConfig(configKey, jvmValue)
    }

    expect:
    def setting = ConfigCollector.get().collect().get(configKey)
    setting.stringValue() == expectedValue
    setting.origin == expectedOrigin

    where:
    configKey                                                 | envValue                                       | jvmValue                  | expectedValue                                                          | expectedOrigin
    // ConfigProvider.getMergedMap
    TracerConfig.TRACE_PEER_SERVICE_MAPPING                   | "service1:best_service,userService:my_service" | "service2:backup_service" | "service2:backup_service,service1:best_service,userService:my_service" | ConfigOrigin.CALCULATED
    // ConfigProvider.getOrderedMap
    TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING | "/asdf/*:/test,/b:some"                        | "/a:prop"                 | "/asdf/*:/test,/b:some,/a:prop"                                        | ConfigOrigin.CALCULATED
    // ConfigProvider.getMergedMapWithOptionalMappings
    TracerConfig.HEADER_TAGS                                  | "j:ten"                                        | "e:five,b:six"            | "e:five,j:ten,b:six"                                                   | ConfigOrigin.CALCULATED
    // ConfigProvider.getMergedMap, but only one source
    TracerConfig.TRACE_PEER_SERVICE_MAPPING                   | "service1:best_service,userService:my_service" | null                      | "service1:best_service,userService:my_service"                         | ConfigOrigin.ENV
  }

  def "default not-null config settings are collected"() {
    expect:
    def setting = ConfigCollector.get().collect().get(configKey)
    setting.origin == ConfigOrigin.DEFAULT
    setting.stringValue() == defaultValue

    where:
    configKey                                                  | defaultValue
    IastConfig.IAST_TELEMETRY_VERBOSITY                        | Verbosity.INFORMATION.toString()
    TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA                   | "v" + SpanNaming.SCHEMA_MIN_VERSION
    GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL                 | new Float(DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL).toString()
    CiVisibilityConfig.CIVISIBILITY_GRADLE_SOURCE_SETS         | "main,test"
    IastConfig.IAST_WEAK_HASH_ALGORITHMS                       | DEFAULT_IAST_WEAK_HASH_ALGORITHMS.join(",")
    TracerConfig.TRACE_HTTP_CLIENT_ERROR_STATUSES              | "400-500"
  }

  def "default null config settings are also collected"() {
    when:
    ConfigSetting cs = ConfigCollector.get().collect().get(configKey)

    then:
    cs.key == configKey
    cs.stringValue() == null
    cs.origin == ConfigOrigin.DEFAULT

    where:
    configKey << [
      GeneralConfig.APPLICATION_KEY,
      TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES,
      JmxFetchConfig.JMX_FETCH_CHECK_PERIOD,
      CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT,
      TracerConfig.TRACE_SAMPLE_RATE,
      TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS,
      TracerConfig.PROXY_NO_PROXY,
    ]
  }

  def "default empty maps and list config settings are collected as empty strings"() {
    when:
    ConfigSetting cs = ConfigCollector.get().collect().get(configKey)

    then:
    cs.key == configKey
    cs.stringValue() == ""
    cs.origin == ConfigOrigin.DEFAULT

    where:
    configKey << [
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
    ConfigCollector.get().collect().get('DD_API_KEY').stringValue() == '<hidden>'
  }

  def "collects common setting default values"() {
    when:
    def settings = ConfigCollector.get().collect()

    then:
    def setting = settings.get(key)

    setting.key == key
    setting.stringValue() == value
    setting.origin == ConfigOrigin.DEFAULT

    where:
    key                      | value
    "trace.enabled"          | "true"
    "profiling.enabled"      | "false"
    "appsec.enabled"         | "inactive"
    "data.streams.enabled"   | "false"
    "trace.tags"             | ""
    "trace.header.tags"      | ""
    "logs.injection.enabled" | "true"
    // defaults to null meaning sample everything but not exactly the same as when explicitly set to 1.0
    "trace.sample.rate"      | null
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
    setting.stringValue() == value
    setting.origin == ConfigOrigin.ENV

    where:
    key                      | value
    "trace.enabled"          | "false"
    "profiling.enabled"      | "true"
    "appsec.enabled"         | "false"
    "data.streams.enabled"   | "true"
    // doesn't preserve ordering for some maps
    "trace.tags"             | "component:web,team:apm"
    // lowercase keys for some maps merged from different sources
    "trace.header.tags"      | "X-Header-Tag-1:header_tag_1,X-Header-Tag-2:header_tag_2".toLowerCase()
    "logs.injection.enabled" | "false"
    "trace.sample.rate"      | "0.3"
  }
}
