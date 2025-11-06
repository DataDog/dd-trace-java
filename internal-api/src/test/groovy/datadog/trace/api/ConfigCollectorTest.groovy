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
import datadog.trace.bootstrap.config.provider.ConfigProvider
import datadog.trace.config.inversion.ConfigHelper
import datadog.trace.test.util.DDSpecification
import datadog.trace.util.ConfigStrings

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_HASH_ALGORITHMS
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL
import static datadog.trace.api.ConfigSetting.ABSENT_SEQ_ID

class ConfigCollectorTest extends DDSpecification {

  def "non-default config settings get collected"() {
    setup:
    injectEnvConfig(ConfigStrings.toEnvVar(configKey), configValue)

    expect:
    def envConfigByKey = ConfigCollector.get().collect().get(ConfigOrigin.ENV)
    def config = envConfigByKey.get(configKey)
    config.stringValue() == configValue
    config.origin == ConfigOrigin.ENV

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
    injectEnvConfig(ConfigStrings.toEnvVar(configKey), envConfigValue)
    if (jvmConfigValue != null) {
      injectSysConfig(configKey, jvmConfigValue)
    }

    when:
    def collected = ConfigCollector.get().collect()

    then:
    def envSetting = collected.get(ConfigOrigin.ENV)
    def envConfig = envSetting.get(configKey)
    envConfig.stringValue() == envConfigValue
    envConfig.origin == ConfigOrigin.ENV
    if (jvmConfigValue != null ) {
      def jvmSetting = collected.get(ConfigOrigin.JVM_PROP)
      def jvmConfig = jvmSetting.get(configKey)
      jvmConfig.stringValue().split(',') as Set == jvmConfigValue.split(',') as Set
      jvmConfig.origin == ConfigOrigin.JVM_PROP
    }


    // TODO: Add a check for which setting the collector recognizes as highest precedence

    where:
    configKey                                                 | envConfigValue                                 | jvmConfigValue            | expectedValue                                                          | expectedOrigin
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
    def defaultConfigByKey = ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT)
    def setting = defaultConfigByKey.get(configKey)
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
    def defaultConfigByKey = ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT)
    ConfigSetting cs = defaultConfigByKey.get(configKey)

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
    def defaultConfigByKey = ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT)
    ConfigSetting cs = defaultConfigByKey.get(configKey)

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
    ConfigCollector.get().put('key1', 'value1', ConfigOrigin.DEFAULT, ABSENT_SEQ_ID)
    ConfigCollector.get().put('key2', 'value2', ConfigOrigin.ENV, ABSENT_SEQ_ID)
    ConfigCollector.get().put('key1', 'value4', ConfigOrigin.REMOTE, ABSENT_SEQ_ID)
    ConfigCollector.get().put('key3', 'value3', ConfigOrigin.JVM_PROP, ABSENT_SEQ_ID)

    then:
    def collected = ConfigCollector.get().collect()
    collected.get(ConfigOrigin.REMOTE).get('key1') == ConfigSetting.of('key1', 'value4', ConfigOrigin.REMOTE)
    collected.get(ConfigOrigin.ENV).get('key2') == ConfigSetting.of('key2', 'value2', ConfigOrigin.ENV)
    collected.get(ConfigOrigin.JVM_PROP).get('key3') == ConfigSetting.of('key3', 'value3', ConfigOrigin.JVM_PROP)
    collected.get(ConfigOrigin.DEFAULT).get('key1') == ConfigSetting.of('key1', 'value1', ConfigOrigin.DEFAULT)
  }


  def "hide pii configuration data"() {
    setup:
    ConfigCollector.get().collect()

    when:
    ConfigCollector.get().put('DD_API_KEY', 'sensitive data', ConfigOrigin.ENV, ABSENT_SEQ_ID)

    then:
    def collected = ConfigCollector.get().collect()
    collected.get(ConfigOrigin.ENV).get('DD_API_KEY').stringValue() == '<hidden>'
  }

  def "collects common setting default values"() {
    when:
    def defaultConfigByKey = ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT)

    then:
    def setting = defaultConfigByKey.get(key)

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
    def envConfigByKey = ConfigCollector.get().collect().get(ConfigOrigin.ENV)

    then:
    def setting = envConfigByKey.get(key)

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

  def "config collector creates ConfigSettings with correct seqId"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    when:
    // Simulate sources with increasing precedence and a default
    ConfigCollector.get().put("test.key", "default", ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID)
    ConfigCollector.get().put("test.key", "env", ConfigOrigin.ENV, 2)
    ConfigCollector.get().put("test.key", "jvm", ConfigOrigin.JVM_PROP, 3)
    ConfigCollector.get().put("test.key", "remote", ConfigOrigin.REMOTE, 4)

    then:
    def collected = ConfigCollector.get().collect()
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.key")
    def envSetting = collected.get(ConfigOrigin.ENV).get("test.key")
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.key")
    def remoteSetting = collected.get(ConfigOrigin.REMOTE).get("test.key")

    defaultSetting.seqId == ConfigSetting.DEFAULT_SEQ_ID
    // Higher precedence = higher seqId
    defaultSetting.seqId < envSetting.seqId
    envSetting.seqId < jvmSetting.seqId
    jvmSetting.seqId < remoteSetting.seqId
  }

  def "config id is null for non-StableConfigSource"() {
    setup:
    def strictness = ConfigHelper.get().configInversionStrictFlag()
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)

    def key = "test.key"
    def value = "test-value"
    injectSysConfig(key, value)

    when:
    // Trigger config collection by getting a value
    ConfigProvider.getInstance().getString(key)
    def settings = ConfigCollector.get().collect()

    then:
    // Verify the config was collected but without a config ID
    def setting = settings.get(ConfigOrigin.JVM_PROP).get(key)
    setting != null
    setting.configId == null
    setting.value == value
    setting.origin == ConfigOrigin.JVM_PROP

    cleanup:
    ConfigHelper.get().setConfigInversionStrict(strictness)
  }

  def "default sources cannot be overridden"() {
    setup:
    def key = "test.key"
    def value = "test-value"
    def overrideVal = "override-value"
    def defaultConfigByKey
    ConfigSetting cs

    when:
    // Need to make 2 calls in a row because collect() will empty the map
    ConfigCollector.get().putDefault(key, value)
    ConfigCollector.get().putDefault(key, overrideVal)
    defaultConfigByKey = ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT)
    cs = defaultConfigByKey.get(key)

    then:
    cs.key == key
    cs.stringValue() == value
    cs.origin == ConfigOrigin.DEFAULT
    cs.seqId == ConfigSetting.DEFAULT_SEQ_ID
  }
}
