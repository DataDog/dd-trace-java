package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification
import spock.lang.Shared
import datadog.trace.api.ConfigCollector
import datadog.trace.api.ConfigOrigin
import datadog.trace.api.ConfigSetting

import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING

class ConfigProviderTest extends DDSpecification {

  @Shared
  ConfigProvider configProvider = ConfigProvider.withoutCollector()

  def "properties take precedence over env vars for ordered map"() {
    setup:
    injectEnvConfig("TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING", "/a:env,/b:env")
    injectSysConfig(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/a:prop")

    when:
    def config = configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING)

    then:
    config["/a"] == "prop"
    config["/b"] == "env"
  }


  def "test config alias priority"() {
    setup:
    injectEnvConfig("CONFIG_NAME", configNameValue)
    injectEnvConfig("CONFIG_ALIAS1", configAlias1Value)
    injectEnvConfig("CONFIG_ALIAS2", configAlias2Value)

    when:
    def config = configProvider.getString("CONFIG_NAME", null, "CONFIG_ALIAS1", "CONFIG_ALIAS2")

    then:
    config == expected

    where:
    configNameValue | configAlias1Value | configAlias2Value | expected
    "default"       | null              | null              | "default"
    null            | "alias1"          | null              | "alias1"
    null            | null              | "alias2"          | "alias2"
    "default"       | "alias1"          | null              | "default"
    "default"       | null              | "alias2"          | "default"
    null            | "alias1"          | "alias2"          | "alias1"
  }

  def "ConfigProvider assigns correct seqId, origin, and value for each source and default"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    injectEnvConfig("DD_TEST_KEY", "envValue")
    injectSysConfig("test.key", "jvmValue")
    // Default ConfigProvider includes ENV and JVM_PROP
    def provider = ConfigProvider.createDefault()

    when:
    def value = provider.getString("test.key", "defaultValue")
    def collected = ConfigCollector.get().collect()

    then:
    // Check the default
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.key")
    defaultSetting.key == "test.key"
    defaultSetting.stringValue() == "defaultValue"
    defaultSetting.origin == ConfigOrigin.DEFAULT
    defaultSetting.seqId == ConfigSetting.DEFAULT_SEQ_ID

    def envSetting = collected.get(ConfigOrigin.ENV).get("test.key")
    envSetting.key == "test.key"
    envSetting.stringValue() == "envValue"
    envSetting.origin == ConfigOrigin.ENV

    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.key")
    jvmSetting.key == "test.key"
    jvmSetting.stringValue() == "jvmValue"
    jvmSetting.origin == ConfigOrigin.JVM_PROP

    // It doesn't matter what the seqId values are, so long as they increase with source precedence
    jvmSetting.seqId > envSetting.seqId
    envSetting.seqId > defaultSetting.seqId

    // The value returned by ConfigProvider should be the highest precedence value
    value == jvmSetting.stringValue()
  }

  def "ConfigProvider reports highest seqId for chosen value and origin regardless of conversion errors"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up: default, env (valid), jvm (invalid for integer)
    injectEnvConfig("DD_TEST_INT", "42")
    injectSysConfig("test.int", "notAnInt")
    def provider = ConfigProvider.createDefault()

    when:
    def value = provider.getInteger("test.int", 7)
    def collected = ConfigCollector.get().collect()

    then:
    // Default
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.int")
    defaultSetting.key == "test.int"
    defaultSetting.stringValue() == "7"
    defaultSetting.origin == ConfigOrigin.DEFAULT
    defaultSetting.seqId == ConfigSetting.DEFAULT_SEQ_ID

    // ENV (valid)
    def envSetting = collected.get(ConfigOrigin.ENV).get("test.int")
    envSetting.key == "test.int"
    envSetting.stringValue() == "42"
    envSetting.origin == ConfigOrigin.ENV

    // JVM_PROP (invalid, should still be reported)
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.int")
    jvmSetting.key == "test.int"
    jvmSetting.stringValue() == "notAnInt"
    jvmSetting.origin == ConfigOrigin.JVM_PROP

    // The chosen value (from ENV) should have been re-reported with the highest seqId
    def maxSeqId = [defaultSetting.seqId, envSetting.seqId, jvmSetting.seqId].max()
    def chosenSetting = [defaultSetting, envSetting, jvmSetting].find { it.seqId == maxSeqId }
    chosenSetting.stringValue() == "42"
    chosenSetting.origin == ConfigOrigin.ENV

    // The value returned by provider should be the valid one
    value == 42
  }
}
