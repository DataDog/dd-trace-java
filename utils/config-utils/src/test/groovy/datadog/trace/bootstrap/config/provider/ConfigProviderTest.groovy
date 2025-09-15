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

  def "ConfigProvider reports highest seqId for chosen value and origin regardless of conversion errors for #methodType"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up: default, env (valid), jvm (invalid for the specific type)
    injectEnvConfig(envKey, validValue)
    injectSysConfig(configKey, invalidValue)
    def provider = ConfigProvider.createDefault()

    when:
    def value = methodCall(provider, configKey, defaultValue)
    def collected = ConfigCollector.get().collect()

    then:
    // Default
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get(configKey)
    defaultSetting.key == configKey
    defaultSetting.stringValue() == String.valueOf(defaultValue)
    defaultSetting.origin == ConfigOrigin.DEFAULT
    defaultSetting.seqId == ConfigSetting.DEFAULT_SEQ_ID

    // ENV (valid)
    def envSetting = collected.get(ConfigOrigin.ENV).get(configKey)
    envSetting.key == configKey
    envSetting.stringValue() == validValue
    envSetting.origin == ConfigOrigin.ENV

    // JVM_PROP (invalid, should still be reported)
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get(configKey)
    jvmSetting.key == configKey
    jvmSetting.stringValue() == invalidValue
    jvmSetting.origin == ConfigOrigin.JVM_PROP

    // The chosen value (from ENV) should have been re-reported with the highest seqId
    def maxSeqId = [defaultSetting.seqId, envSetting.seqId, jvmSetting.seqId].max()
    def chosenSetting = [defaultSetting, envSetting, jvmSetting].find { it.seqId == maxSeqId }
    chosenSetting.stringValue() == validValue
    chosenSetting.origin == ConfigOrigin.ENV

    // The value returned by provider should be the valid one
    value == expectedResult

    where:
    // getBoolean is purposefully excluded; see getBoolean test below
    methodType   | configKey     | envKey          | validValue | invalidValue | defaultValue | expectedResult | methodCall
    "getInteger" | "test.int"    | "DD_TEST_INT"   | "42"       | "notAnInt"   | 7            | 42             | { configProvider, key, defVal -> configProvider.getInteger(key, defVal) }
    "getLong"    | "test.long"   | "DD_TEST_LONG"  | "123"      | "notALong"   | 5L           | 123L           | { configProvider, key, defVal -> configProvider.getLong(key, defVal) }
    "getFloat"   | "test.float"  | "DD_TEST_FLOAT" | "42.5"     | "notAFloat"  | 3.14f        | 42.5f          | { configProvider, key, defVal -> configProvider.getFloat(key, defVal) }
    "getDouble"  | "test.double" | "DD_TEST_DOUBLE"| "42.75"    | "notADouble" | 2.71         | 42.75          | { configProvider, key, defVal -> configProvider.getDouble(key, defVal) }
  }

  def "ConfigProvider transforms invalid values for getBoolean to false with CALCULATED origin"() {
    // Booleans are a special case; we currently treat all invalid boolean configurations as false rather than falling back to a lower precedence setting.
    setup:
    ConfigCollector.get().collect() // clear previous state

    def envKey = "DD_TEST_BOOL"
    def envValue = "true"
    def configKey = "test.bool"
    def propValue = "notABool"
    def defaultValue = true

    injectEnvConfig(envKey, envValue)
    injectSysConfig(configKey, propValue)
    def provider = ConfigProvider.createDefault()

    when:
    def value = provider.getBoolean(configKey, defaultValue)
    def collected = ConfigCollector.get().collect()

    then:
    // Default
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get(configKey)
    defaultSetting.key == configKey
    defaultSetting.stringValue() == String.valueOf(defaultValue)
    defaultSetting.origin == ConfigOrigin.DEFAULT
    defaultSetting.seqId == ConfigSetting.DEFAULT_SEQ_ID

    // ENV (valid)
    def envSetting = collected.get(ConfigOrigin.ENV).get(configKey)
    envSetting.key == configKey
    envSetting.stringValue() == envValue
    envSetting.origin == ConfigOrigin.ENV

    // JVM_PROP (invalid, should still be reported)
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get(configKey)
    jvmSetting.key == configKey
    jvmSetting.stringValue() == propValue
    jvmSetting.origin == ConfigOrigin.JVM_PROP

    // Config was evaluated to false and reported with CALCULATED origin
    def calcSetting = collected.get(ConfigOrigin.CALCULATED).get(configKey)
    calcSetting.key == configKey
    calcSetting.stringValue() == "false"
    calcSetting.origin == ConfigOrigin.CALCULATED

    // The highest seqId should be the CALCULATED origin
    def maxSeqId = [defaultSetting.seqId, envSetting.seqId, jvmSetting.seqId, calcSetting.seqId].max()
    def chosenSetting = [defaultSetting, envSetting, jvmSetting, calcSetting].find { it.seqId == maxSeqId }
    chosenSetting.origin == ConfigOrigin.CALCULATED
    chosenSetting.stringValue() == "false"

    // The value returned by provider should be false
    value == false
  }

  def "ConfigProvider getEnum returns default when conversion fails"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up: only invalid enum values from all sources
    injectEnvConfig("DD_TEST_ENUM2", "NOT_A_VALID_ENUM")
    injectSysConfig("test.enum2", "ALSO_INVALID")
    def provider = ConfigProvider.createDefault()

    when:
    def value = provider.getEnum("test.enum2", ConfigOrigin, ConfigOrigin.CODE)
    def collected = ConfigCollector.get().collect()

    then:
    // Should have attempted to use the highest precedence value (JVM_PROP)
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.enum2")
    jvmSetting.stringValue() == "ALSO_INVALID"

    // But since conversion failed, should return the default
    value == ConfigOrigin.CODE
  }

  def "ConfigProvider getString reports all sources and respects precedence"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up: default, env, jvm (all valid strings)
    injectEnvConfig("DD_TEST_STRING", "envValue")
    injectSysConfig("test.string", "jvmValue")
    def provider = ConfigProvider.createDefault()

    when:
    def value = provider.getString("test.string", "defaultValue")
    def collected = ConfigCollector.get().collect()

    then:
    // Default
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.string")
    defaultSetting.key == "test.string"
    defaultSetting.stringValue() == "defaultValue"
    defaultSetting.origin == ConfigOrigin.DEFAULT
    defaultSetting.seqId == ConfigSetting.DEFAULT_SEQ_ID

    // ENV
    def envSetting = collected.get(ConfigOrigin.ENV).get("test.string")
    envSetting.key == "test.string"
    envSetting.stringValue() == "envValue"
    envSetting.origin == ConfigOrigin.ENV

    // JVM_PROP (highest precedence)
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.string")
    jvmSetting.key == "test.string"
    jvmSetting.stringValue() == "jvmValue"
    jvmSetting.origin == ConfigOrigin.JVM_PROP

    // JVM should have highest seqId and be the returned value
    jvmSetting.seqId > envSetting.seqId
    envSetting.seqId > defaultSetting.seqId
    value == "jvmValue"
  }

  def "ConfigProvider getStringNotEmpty reports all values even if they are empty, but returns the non-empty value"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up: env (empty/blank), jvm (valid but with whitespace)
    injectEnvConfig("DD_TEST_STRING_NOT_EMPTY", "  ") // blank string
    injectSysConfig("test.string.not.empty", "  jvmValue  ") // valid but with whitespace
    def provider = ConfigProvider.createDefault()

    when:
    def value = provider.getStringNotEmpty("test.string.not.empty", "defaultValue")
    def collected = ConfigCollector.get().collect()

    then:
    // Default
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.string.not.empty")
    defaultSetting.stringValue() == "defaultValue"

    // ENV (blank, should be skipped for return value but still reported)
    def envSetting = collected.get(ConfigOrigin.ENV).get("test.string.not.empty")
    envSetting.stringValue() == "  "
    envSetting.origin == ConfigOrigin.ENV

    // JVM_PROP setting - should have highest seqId
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.string.not.empty")
    jvmSetting.stringValue() == "  jvmValue  "
    jvmSetting.origin == ConfigOrigin.JVM_PROP

    def maxSeqId = [defaultSetting.seqId, envSetting.seqId, jvmSetting.seqId].max()
    jvmSetting.seqId == maxSeqId

    value == "  jvmValue  "
  }

  def "ConfigProvider getStringExcludingSource excludes specified source type"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up: env, jvm (both valid)
    injectEnvConfig("DD_TEST_STRING_EXCLUDE", "envValue")
    injectSysConfig("test.string.exclude", "jvmValue")
    def provider = ConfigProvider.createDefault()

    when:
    // Exclude JVM_PROP source, should fall back to ENV
    def value = provider.getStringExcludingSource("test.string.exclude", "defaultValue",
      SystemPropertiesConfigSource)
    def collected = ConfigCollector.get().collect()

    then:
    // Default
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.string.exclude")
    defaultSetting.stringValue() == "defaultValue"

    // ENV (should be used since JVM is excluded)
    def envSetting = collected.get(ConfigOrigin.ENV).get("test.string.exclude")
    envSetting.stringValue() == "envValue"
    envSetting.origin == ConfigOrigin.ENV

    // Should return ENV value since JVM source was excluded
    value == "envValue"
  }

  def "ConfigProvider getMergedMap merges maps from multiple sources with correct precedence"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up: env (partial map), jvm (partial map with overlap)
    injectEnvConfig("DD_TEST_MAP", "env_key:env_value,shared:from_env")
    injectSysConfig("test.map", "jvm_key:jvm_value,shared:from_jvm")
    def provider = ConfigProvider.createDefault()

    when:
    def result = provider.getMergedMap("test.map")
    def collected = ConfigCollector.get().collect()

    then:
    // Result should be merged with JVM taking precedence
    result == [
      "env_key": "env_value",    // from ENV
      "jvm_key": "jvm_value",    // from JVM
      "shared": "from_jvm"       // JVM overrides ENV
    ]

    // Default should be reported as empty
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.map")
    defaultSetting.value == [:]
    defaultSetting.origin == ConfigOrigin.DEFAULT

    // ENV map should be reported
    def envSetting = collected.get(ConfigOrigin.ENV).get("test.map")
    envSetting.value == ["env_key": "env_value", "shared": "from_env"]
    envSetting.origin == ConfigOrigin.ENV

    // JVM map should be reported
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.map")
    jvmSetting.value == ["jvm_key": "jvm_value", "shared": "from_jvm"]
    jvmSetting.origin == ConfigOrigin.JVM_PROP

    // Final calculated result should be reported with highest seqId
    def calculatedSetting = collected.get(ConfigOrigin.CALCULATED).get("test.map")
    calculatedSetting.value == result
    calculatedSetting.origin == ConfigOrigin.CALCULATED

    // Calculated should have highest seqId
    def maxSeqId = [defaultSetting.seqId, envSetting.seqId, jvmSetting.seqId, calculatedSetting.seqId].max()
    calculatedSetting.seqId == maxSeqId
  }

  def "ConfigProvider getMergedTagsMap handles trace tags format"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up trace tags format (can use comma or space separators)
    injectEnvConfig("DD_TEST_TAGS", "service:web,version:1.0")
    injectSysConfig("test.tags", "env:prod team:backend")
    def provider = ConfigProvider.createDefault()

    when:
    def result = provider.getMergedTagsMap("test.tags")
    def collected = ConfigCollector.get().collect()

    then:
    // Should merge both tag formats
    result == [
      "service": "web",      // from ENV
      "version": "1.0",      // from ENV
      "env": "prod",         // from JVM
      "team": "backend"      // from JVM
    ]

    // Should report individual sources and calculated result
    def envSetting = collected.get(ConfigOrigin.ENV).get("test.tags")
    envSetting.value == ["service": "web", "version": "1.0"]

    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.tags")
    jvmSetting.value == ["env": "prod", "team": "backend"]

    def calculatedSetting = collected.get(ConfigOrigin.CALCULATED).get("test.tags")
    calculatedSetting.value == result
  }

  def "ConfigProvider getMergedMapWithOptionalMappings handles multiple keys and transformations"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up multiple keys with optional mappings
    injectEnvConfig("DD_HEADER_1", "X-Custom-Header:custom.tag")
    injectSysConfig("header.1", "X-Auth:auth.tag")
    injectEnvConfig("DD_HEADER_2", "X-Request-ID")  // key only, should get prefix
    def provider = ConfigProvider.createDefault()

    when:
    def result = provider.getMergedMapWithOptionalMappings("trace.http", true, "header.1", "header.2")
    def collected = ConfigCollector.get().collect()

    then:
    // Should merge with transformations
    result.size() >= 2
    result["x-custom-header"] == "custom.tag"    // from ENV header.1
    result["x-auth"] == "auth.tag"               // from JVM header.1
    result["x-request-id"] != null               // from ENV header.2, should get prefix

    // Should report sources and calculated result
    def calculatedSetting = collected.get(ConfigOrigin.CALCULATED).get("header.2")  // Last key processed
    calculatedSetting != null
    calculatedSetting.origin == ConfigOrigin.CALCULATED
  }

  def "ConfigProvider getOrderedMap preserves insertion order and merges sources"() {
    setup:
    ConfigCollector.get().collect() // clear previous state

    // Set up ordered maps from multiple sources
    injectEnvConfig("DD_TEST_ORDERED_MAP", "first:env_first,second:env_second,third:env_third")
    injectSysConfig("test.ordered.map", "second:jvm_second,fourth:jvm_fourth,first:jvm_first")
    def provider = ConfigProvider.createDefault()

    when:
    def result = provider.getOrderedMap("test.ordered.map")
    def collected = ConfigCollector.get().collect()

    then:
    // Result should be a LinkedHashMap with preserved order and JVM precedence
    result instanceof LinkedHashMap
    result == [
      "first": "jvm_first",      // JVM overrides ENV, appears first due to ENV order
      "second": "jvm_second",    // JVM overrides ENV, appears second due to ENV order
      "third": "env_third",      // only in ENV, appears third due to ENV order
      "fourth": "jvm_fourth"     // only in JVM, appears last due to JVM addition
    ]

    // Verify order is preserved (LinkedHashMap maintains insertion order)
    def keys = result.keySet() as List
    keys == ["first", "second", "third", "fourth"]

    // Default should be reported as empty
    def defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.ordered.map")
    defaultSetting.value == [:]
    defaultSetting.origin == ConfigOrigin.DEFAULT

    // ENV ordered map should be reported
    def envSetting = collected.get(ConfigOrigin.ENV).get("test.ordered.map")
    envSetting.value == ["first": "env_first", "second": "env_second", "third": "env_third"]
    envSetting.origin == ConfigOrigin.ENV

    // JVM ordered map should be reported
    def jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.ordered.map")
    jvmSetting.value == ["second": "jvm_second", "fourth": "jvm_fourth", "first": "jvm_first"]
    jvmSetting.origin == ConfigOrigin.JVM_PROP

    // Final calculated result should be reported with highest seqId
    def calculatedSetting = collected.get(ConfigOrigin.CALCULATED).get("test.ordered.map")
    calculatedSetting.value == result
    calculatedSetting.origin == ConfigOrigin.CALCULATED

    // Calculated should have highest seqId
    def maxSeqId = [defaultSetting.seqId, envSetting.seqId, jvmSetting.seqId, calculatedSetting.seqId].max()
    calculatedSetting.seqId == maxSeqId
  }

  def "ConfigProvider methods that call getString internally report their own defaults before getString's null default"() {
    setup:
    ConfigCollector.get().collect() // clear previous state
    // No environment or system property values set, so methods should fall back to their defaults
    def provider = ConfigProvider.createDefault()

    when:
    def enumResult = provider.getEnum("test.enum", ConfigOrigin, ConfigOrigin.CODE)
    def listResult = provider.getList("test.list", ["default", "list"])
    def setResult = provider.getSet("test.set", ["default", "set"] as Set)
    def rangeResult = provider.getIntegerRange("test.range", new BitSet())
    def collected = ConfigCollector.get().collect()

    then:
    // Each method should have reported its own default, not getString's null default

    def enumDefault = collected.get(ConfigOrigin.DEFAULT).get("test.enum")
    enumDefault.stringValue() == "CODE" // ConfigOrigin.CODE.name()
    enumDefault.origin == ConfigOrigin.DEFAULT
    enumDefault.seqId == ConfigSetting.DEFAULT_SEQ_ID

    def listDefault = collected.get(ConfigOrigin.DEFAULT).get("test.list")
    listDefault.value == ["default", "list"]
    listDefault.origin == ConfigOrigin.DEFAULT
    listDefault.seqId == ConfigSetting.DEFAULT_SEQ_ID

    def setDefault = collected.get(ConfigOrigin.DEFAULT).get("test.set")
    setDefault.value == ["default", "set"] as Set
    setDefault.origin == ConfigOrigin.DEFAULT
    setDefault.seqId == ConfigSetting.DEFAULT_SEQ_ID

    def rangeDefault = collected.get(ConfigOrigin.DEFAULT).get("test.range")
    rangeDefault.value == new BitSet()
    rangeDefault.origin == ConfigOrigin.DEFAULT
    rangeDefault.seqId == ConfigSetting.DEFAULT_SEQ_ID

    // Verify the methods returned their default values (not null)
    enumResult == ConfigOrigin.CODE
    listResult == ["default", "list"]
    setResult == ["default", "set"] as Set
    rangeResult == new BitSet()
  }

  // NOTE: This is a case that SHOULD never occur. #reReportToCollector(String, int) should only be called with valid origins
  def "ConfigValueResolver reReportToCollector handles null origin gracefully"() {
    setup:
    ConfigCollector.get().collect() // clear previous state
    ConfigProvider.ConfigValueResolver resolver = ConfigProvider.ConfigValueResolver.of("1")

    when:
    resolver.reReportToCollector("test.key", 5)

    then:
    0 * ConfigCollector.get().put(_, _, _, _, _)
  }

  def "ConfigMergeResolver reports correct origin for single vs multiple source contributions"() {
    setup:
    ConfigCollector.get().collect() // clear previous state
    def provider = ConfigProvider.createDefault()

    when: "Only ENV source contributes to merged map"
    injectEnvConfig("DD_SINGLE_SOURCE_MAP", "key1:value1,key2:value2")
    // No JVM prop set, so only ENV contributes
    def singleSourceResult = provider.getMergedMap("single.source.map")
    def singleSourceCollected = ConfigCollector.get().collect()

    then: "Should report with ENV origin, not CALCULATED"
    singleSourceResult == ["key1": "value1", "key2": "value2"]

    // Should have DEFAULT for default value
    def singleDefault = singleSourceCollected.get(ConfigOrigin.DEFAULT)?.get("single.source.map")
    singleDefault?.value == [:]

    // Should have ENV for the actual value (not CALCULATED)
    def singleEnv = singleSourceCollected.get(ConfigOrigin.ENV)?.get("single.source.map")
    singleEnv?.value == ["key1": "value1", "key2": "value2"]
    singleEnv?.origin == ConfigOrigin.ENV

    // Should NOT have CALCULATED entry since only one source contributed
    singleSourceCollected.get(ConfigOrigin.CALCULATED)?.get("single.source.map") == null

    when: "Multiple sources contribute to merged map"
    ConfigCollector.get().collect() // clear for next test
    injectEnvConfig("DD_MULTI_SOURCE_MAP", "env_key:env_value,shared:from_env")
    injectSysConfig("multi.source.map", "jvm_key:jvm_value,shared:from_jvm")
    def multiSourceResult = provider.getMergedMap("multi.source.map")
    def multiSourceCollected = ConfigCollector.get().collect()

    then: "Should report with CALCULATED origin when multiple sources contribute"
    multiSourceResult == ["env_key": "env_value", "jvm_key": "jvm_value", "shared": "from_jvm"]

    // Should have CALCULATED for the final merged result
    def multiCalculated = multiSourceCollected.get(ConfigOrigin.CALCULATED)?.get("multi.source.map")
    multiCalculated?.value == multiSourceResult
    multiCalculated?.origin == ConfigOrigin.CALCULATED
  }
}
