package datadog.trace.bootstrap.config.provider;

import static datadog.trace.api.ConfigOrigin.CALCULATED;
import static datadog.trace.api.ConfigOrigin.CODE;
import static datadog.trace.api.ConfigOrigin.DEFAULT;
import static datadog.trace.api.ConfigOrigin.ENV;
import static datadog.trace.api.ConfigOrigin.JVM_PROP;
import static datadog.trace.api.ConfigSetting.DEFAULT_SEQ_ID;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class ConfigProviderTest {

  private static final ConfigProvider configProvider = ConfigProvider.withoutCollector();

  private StrictnessPolicy strictness;

  @BeforeEach
  void setup() {
    strictness = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.TEST);
  }

  @AfterEach
  void cleanup() {
    ConfigHelper.get().setConfigInversionStrict(strictness);
  }

  @Test
  @WithConfig(
      key = "TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING",
      value = "/a:env,/b:env",
      env = true)
  @WithConfig(key = TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, value = "/a:prop")
  void propertiesTakePrecedenceOverEnvVarsForOrderedMap() {
    Map<String, String> config =
        configProvider.getOrderedMap(TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING);

    assertEquals("prop", config.get("/a"));
    assertEquals("env", config.get("/b"));
  }

  @TableTest({
    "scenario           | configNameValue | configAlias1Value | configAlias2Value | expected",
    "name only          | default         |                   |                   | default ",
    "alias1 only        |                 | alias1            |                   | alias1  ",
    "alias2 only        |                 |                   | alias2            | alias2  ",
    "name over alias1   | default         | alias1            |                   | default ",
    "name over alias2   | default         |                   | alias2            | default ",
    "alias1 over alias2 |                 | alias1            | alias2            | alias1  "
  })
  void testConfigAliasPriority(
      String configNameValue, String configAlias1Value, String configAlias2Value, String expected) {
    injectEnvConfig("CONFIG_NAME", configNameValue);
    injectEnvConfig("CONFIG_ALIAS1", configAlias1Value);
    injectEnvConfig("CONFIG_ALIAS2", configAlias2Value);

    String config = configProvider.getString("CONFIG_NAME", null, "CONFIG_ALIAS1", "CONFIG_ALIAS2");

    assertEquals(expected, config);
  }

  @Test
  @WithConfig(key = "TEST_KEY", value = "envValue", env = true)
  @WithConfig(key = "test.key", value = "jvmValue")
  void configProviderAssignsCorrectSeqIdOriginAndValueForEachSourceAndDefault() {
    ConfigCollector.get().collect(); // clear previous state
    // Default ConfigProvider includes ENV and JVM_PROP
    ConfigProvider provider = ConfigProvider.createDefault();

    String value = provider.getString("test.key", "defaultValue");
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Check the default
    ConfigSetting defaultSetting = collected.get(DEFAULT).get("test.key");
    assertEquals("defaultValue", defaultSetting.stringValue());
    assertEquals(DEFAULT, defaultSetting.origin);
    assertEquals(DEFAULT_SEQ_ID, defaultSetting.seqId);

    ConfigSetting envSetting = collected.get(ENV).get("test.key");
    assertEquals("envValue", envSetting.stringValue());
    assertEquals(ENV, envSetting.origin);

    ConfigSetting jvmSetting = collected.get(JVM_PROP).get("test.key");
    assertEquals("jvmValue", jvmSetting.stringValue());
    assertEquals(JVM_PROP, jvmSetting.origin);

    // It doesn't matter what the seqId values are, so long as they increase with source precedence
    assertTrue(jvmSetting.seqId > envSetting.seqId);
    assertTrue(envSetting.seqId > defaultSetting.seqId);

    // The value returned by ConfigProvider should be the highest precedence value
    assertEquals(jvmSetting.stringValue(), value);
  }

  @ParameterizedTest(
      name =
          "ConfigProvider reports highest seqId for chosen value and origin regardless of conversion errors for {0}")
  @MethodSource("configProviderReportsHighestSeqIdForChosenValueArguments")
  void configProviderReportsHighestSeqIdForChosenValue(
      String methodType,
      String configKey,
      String envKey,
      String validValue,
      String invalidValue,
      Object defaultValue,
      Object expectedResult,
      TypedGetter methodCall) {
    ConfigCollector.get().collect(); // clear previous state

    // Set up: default, env (valid), jvm (invalid for the specific type)
    injectEnvConfig(envKey, validValue);
    injectSysConfig(configKey, invalidValue);
    ConfigProvider provider = ConfigProvider.createDefault();

    Object value = methodCall.get(provider, configKey, defaultValue);
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Default
    ConfigSetting defaultSetting = collected.get(DEFAULT).get(configKey);
    assertEquals(String.valueOf(defaultValue), defaultSetting.stringValue());
    assertEquals(DEFAULT, defaultSetting.origin);
    assertEquals(DEFAULT_SEQ_ID, defaultSetting.seqId);

    // ENV (valid)
    ConfigSetting envSetting = collected.get(ENV).get(configKey);
    assertEquals(validValue, envSetting.stringValue());
    assertEquals(ENV, envSetting.origin);

    // JVM_PROP (invalid, should still be reported)
    ConfigSetting jvmSetting = collected.get(JVM_PROP).get(configKey);
    assertEquals(invalidValue, jvmSetting.stringValue());
    assertEquals(JVM_PROP, jvmSetting.origin);

    // The chosen value (from ENV) should have been re-reported with the highest seqId
    ConfigSetting chosenSetting = highestSeqIdSetting(defaultSetting, envSetting, jvmSetting);
    assertEquals(validValue, chosenSetting.stringValue());
    assertEquals(ENV, chosenSetting.origin);

    // The value returned by provider should be the valid one
    assertEquals(expectedResult, value);
  }

  static Stream<Arguments> configProviderReportsHighestSeqIdForChosenValueArguments() {
    // getBoolean is purposefully excluded; see getBoolean test below
    return Stream.of(
        arguments(
            "getInteger",
            "test.int",
            "DD_TEST_INT",
            "42",
            "notAnInt",
            7,
            42,
            (TypedGetter)
                (provider, key, defaultValue) -> provider.getInteger(key, (int) defaultValue)),
        arguments(
            "getLong",
            "test.long",
            "DD_TEST_LONG",
            "123",
            "notALong",
            5L,
            123L,
            (TypedGetter)
                (provider, key, defaultValue) -> provider.getLong(key, (long) defaultValue)),
        arguments(
            "getFloat",
            "test.float",
            "DD_TEST_FLOAT",
            "42.5",
            "notAFloat",
            3.14f,
            42.5f,
            (TypedGetter)
                (provider, key, defaultValue) -> provider.getFloat(key, (float) defaultValue)),
        arguments(
            "getDouble",
            "test.double",
            "DD_TEST_DOUBLE",
            "42.75",
            "notADouble",
            2.71,
            42.75,
            (TypedGetter)
                (provider, key, defaultValue) -> provider.getDouble(key, (double) defaultValue)));
  }

  @Test
  @WithConfig(key = "TEST_BOOL", value = "true", env = true)
  @WithConfig(key = "test.bool", value = "notABool")
  void configProviderTransformsInvalidValuesForGetBooleanToFalseWithCalculatedOrigin() {
    // Booleans are a special case; we currently treat all invalid boolean configurations as false
    // rather than falling back to a lower precedence setting.
    ConfigCollector.get().collect(); // clear previous state

    String envValue = "true";
    String configKey = "test.bool";
    String propValue = "notABool";
    boolean defaultValue = true;

    ConfigProvider provider = ConfigProvider.createDefault();

    boolean value = provider.getBoolean(configKey, defaultValue);
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Default
    ConfigSetting defaultSetting = collected.get(DEFAULT).get(configKey);
    assertEquals(String.valueOf(defaultValue), defaultSetting.stringValue());
    assertEquals(DEFAULT, defaultSetting.origin);
    assertEquals(DEFAULT_SEQ_ID, defaultSetting.seqId);

    // ENV (valid)
    ConfigSetting envSetting = collected.get(ENV).get(configKey);
    assertEquals(envValue, envSetting.stringValue());
    assertEquals(ENV, envSetting.origin);

    // JVM_PROP (invalid, should still be reported)
    ConfigSetting jvmSetting = collected.get(JVM_PROP).get(configKey);
    assertEquals(propValue, jvmSetting.stringValue());
    assertEquals(JVM_PROP, jvmSetting.origin);

    // Config was evaluated to false and reported with CALCULATED origin
    ConfigSetting calcSetting = collected.get(CALCULATED).get(configKey);
    assertEquals("false", calcSetting.stringValue());
    assertEquals(CALCULATED, calcSetting.origin);

    // The highest seqId should be the CALCULATED origin
    ConfigSetting chosenSetting =
        highestSeqIdSetting(defaultSetting, envSetting, jvmSetting, calcSetting);
    assertEquals(CALCULATED, chosenSetting.origin);
    assertEquals("false", chosenSetting.stringValue());

    // The value returned by provider should be false
    assertFalse(value);
  }

  @Test
  // Set up: only invalid enum values from all sources
  @WithConfig(key = "TEST_ENUM2", value = "NOT_A_VALID_ENUM", env = true)
  @WithConfig(key = "test.enum2", value = "ALSO_INVALID")
  void configProviderGetEnumReturnsDefaultWhenConversionFails() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    ConfigOrigin value = provider.getEnum("test.enum2", ConfigOrigin.class, CODE);
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Should have attempted to use the highest precedence value (JVM_PROP)
    ConfigSetting jvmSetting = collected.get(JVM_PROP).get("test.enum2");
    assertEquals("ALSO_INVALID", jvmSetting.stringValue());

    // But since conversion failed, should return the default
    assertEquals(CODE, value);
  }

  @Test
  // Set up: default, env, jvm (all valid strings)
  @WithConfig(key = "TEST_STRING", value = "envValue", env = true)
  @WithConfig(key = "test.string", value = "jvmValue")
  void configProviderGetStringReportsAllSourcesAndRespectsPrecedence() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    String value = provider.getString("test.string", "defaultValue");
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Default
    ConfigSetting defaultSetting = collected.get(DEFAULT).get("test.string");
    assertEquals("defaultValue", defaultSetting.stringValue());
    assertEquals(DEFAULT, defaultSetting.origin);
    assertEquals(DEFAULT_SEQ_ID, defaultSetting.seqId);

    // ENV
    ConfigSetting envSetting = collected.get(ENV).get("test.string");
    assertEquals("envValue", envSetting.stringValue());
    assertEquals(ENV, envSetting.origin);

    // JVM_PROP (highest precedence)
    ConfigSetting jvmSetting = collected.get(JVM_PROP).get("test.string");
    assertEquals("jvmValue", jvmSetting.stringValue());
    assertEquals(JVM_PROP, jvmSetting.origin);

    // JVM should have highest seqId and be the returned value
    assertTrue(jvmSetting.seqId > envSetting.seqId);
    assertTrue(envSetting.seqId > defaultSetting.seqId);
    assertEquals("jvmValue", value);
  }

  @Test
  // Set up: env (empty/blank), jvm (valid but with whitespace)
  @WithConfig(key = "TEST_STRING_NOT_EMPTY", value = "  ", env = true) // blank string
  @WithConfig(key = "test.string.not.empty", value = "  jvmValue  ") // valid but with whitespace
  void configProviderGetStringNotEmptyReportsAllValuesEvenIfEmptyButReturnsNonEmptyValue() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    String value = provider.getStringNotEmpty("test.string.not.empty", "defaultValue");
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Default
    ConfigSetting defaultSetting = collected.get(DEFAULT).get("test.string.not.empty");
    assertEquals("defaultValue", defaultSetting.stringValue());

    // ENV (blank, should be skipped for return value but still reported)
    ConfigSetting envSetting = collected.get(ENV).get("test.string.not.empty");
    assertEquals("  ", envSetting.stringValue());
    assertEquals(ENV, envSetting.origin);

    // JVM_PROP setting - should have highest seqId
    ConfigSetting jvmSetting = collected.get(JVM_PROP).get("test.string.not.empty");
    assertEquals("  jvmValue  ", jvmSetting.stringValue());
    assertEquals(JVM_PROP, jvmSetting.origin);

    assertEquals(maxSeqId(defaultSetting, envSetting, jvmSetting), jvmSetting.seqId);

    assertEquals("  jvmValue  ", value);
  }

  @Test
  // Set up: env, jvm (both valid)
  @WithConfig(key = "TEST_STRING_EXCLUDE", value = "envValue", env = true)
  @WithConfig(key = "test.string.exclude", value = "jvmValue")
  void configProviderGetStringExcludingSourceExcludesSpecifiedSourceType() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    // Exclude JVM_PROP source, should fall back to ENV
    String value =
        provider.getStringExcludingSource(
            "test.string.exclude", "defaultValue", SystemPropertiesConfigSource.class);
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Default
    ConfigSetting defaultSetting = collected.get(DEFAULT).get("test.string.exclude");
    assertEquals("defaultValue", defaultSetting.stringValue());

    // ENV (should be used since JVM is excluded)
    ConfigSetting envSetting = collected.get(ENV).get("test.string.exclude");
    assertEquals("envValue", envSetting.stringValue());
    assertEquals(ENV, envSetting.origin);

    // Should return ENV value since JVM source was excluded
    assertEquals("envValue", value);
  }

  @Test
  // Set up: env (partial map), jvm (partial map with overlap)
  @WithConfig(key = "TEST_MAP", value = "env_key:env_value,shared:from_env", env = true)
  @WithConfig(key = "test.map", value = "jvm_key:jvm_value,shared:from_jvm")
  void configProviderGetMergedMapMergesMapsFromMultipleSourcesWithCorrectPrecedence() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    Map<String, String> result = provider.getMergedMap("test.map");
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Result should be merged with JVM taking precedence
    assertEquals(
        mapOf(
            "env_key", "env_value", // from ENV
            "jvm_key", "jvm_value", // from JVM
            "shared", "from_jvm" // JVM overrides ENV
            ),
        result);

    // Default should be reported as empty
    ConfigSetting defaultSetting = collected.get(DEFAULT).get("test.map");
    assertEquals(emptyMap(), defaultSetting.value);
    assertEquals(DEFAULT, defaultSetting.origin);

    // ENV map should be reported
    ConfigSetting envSetting = collected.get(ENV).get("test.map");
    assertEquals(mapOf("env_key", "env_value", "shared", "from_env"), envSetting.value);
    assertEquals(ENV, envSetting.origin);

    // JVM map should be reported
    ConfigSetting jvmSetting = collected.get(JVM_PROP).get("test.map");
    assertEquals(mapOf("jvm_key", "jvm_value", "shared", "from_jvm"), jvmSetting.value);
    assertEquals(JVM_PROP, jvmSetting.origin);

    // Final calculated result should be reported with highest seqId
    ConfigSetting calculatedSetting = collected.get(CALCULATED).get("test.map");
    assertEquals(result, calculatedSetting.value);
    assertEquals(CALCULATED, calculatedSetting.origin);

    // Calculated should have highest seqId
    assertEquals(
        maxSeqId(defaultSetting, envSetting, jvmSetting, calculatedSetting),
        calculatedSetting.seqId);
  }

  @Test
  // Set up trace tags format (can use comma or space separators)
  @WithConfig(key = "TEST_TAGS", value = "service:web,version:1.0", env = true)
  @WithConfig(key = "test.tags", value = "env:prod team:backend")
  void configProviderGetMergedTagsMapHandlesTraceTagsFormat() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    Map<String, String> result = provider.getMergedTagsMap("test.tags");
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Should merge both tag formats
    assertEquals(
        mapOf(
            "service", "web", // from ENV
            "version", "1.0", // from ENV
            "env", "prod", // from JVM
            "team", "backend" // from JVM
            ),
        result);

    // Should report individual sources and calculated result
    ConfigSetting envSetting = collected.get(ENV).get("test.tags");
    assertEquals(mapOf("service", "web", "version", "1.0"), envSetting.value);

    ConfigSetting jvmSetting = collected.get(JVM_PROP).get("test.tags");
    assertEquals(mapOf("env", "prod", "team", "backend"), jvmSetting.value);

    ConfigSetting calculatedSetting = collected.get(CALCULATED).get("test.tags");
    assertEquals(result, calculatedSetting.value);
  }

  @Test
  // Set up multiple keys with optional mappings
  @WithConfig(key = "HEADER_1", value = "X-Custom-Header:custom.tag", env = true)
  @WithConfig(key = "header.1", value = "X-Auth:auth.tag")
  @WithConfig(key = "HEADER_2", value = "X-Request-ID", env = true) // key only, should get prefix
  void configProviderGetMergedMapWithOptionalMappingsHandlesMultipleKeysAndTransformations() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    Map<String, String> result =
        provider.getMergedMapWithOptionalMappings("trace.http", true, "header.1", "header.2");
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Should merge with transformations
    assertTrue(result.size() >= 2);
    assertEquals("custom.tag", result.get("x-custom-header")); // from ENV header.1
    assertEquals("auth.tag", result.get("x-auth")); // from JVM header.1
    assertNotNull(result.get("x-request-id")); // from ENV header.2, should get prefix

    // Should report sources and calculated result
    ConfigSetting calculatedSetting =
        collected.get(CALCULATED).get("header.2"); // Last key processed
    assertNotNull(calculatedSetting);
    assertEquals(CALCULATED, calculatedSetting.origin);
  }

  @Test
  // Set up ordered maps from multiple sources
  @WithConfig(
      key = "TEST_ORDERED_MAP",
      value = "first:env_first,second:env_second,third:env_third",
      env = true)
  @WithConfig(
      key = "test.ordered.map",
      value = "second:jvm_second,fourth:jvm_fourth,first:jvm_first")
  void configProviderGetOrderedMapPreservesInsertionOrderAndMergesSources() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    Map<String, String> result = provider.getOrderedMap("test.ordered.map");
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Result should be a LinkedHashMap with preserved order and JVM precedence
    assertInstanceOf(LinkedHashMap.class, result);
    assertEquals(
        mapOf(
            "first", "jvm_first", // JVM overrides ENV, appears first due to ENV order
            "second", "jvm_second", // JVM overrides ENV, appears second due to ENV order
            "third", "env_third", // only in ENV, appears third due to ENV order
            "fourth", "jvm_fourth" // only in JVM, appears last due to JVM addition
            ),
        result);

    // Verify order is preserved (LinkedHashMap maintains insertion order)
    List<String> keys = asList(result.keySet().toArray(new String[0]));
    assertEquals(asList("first", "second", "third", "fourth"), keys);

    // Default should be reported as empty
    ConfigSetting defaultSetting = collected.get(DEFAULT).get("test.ordered.map");
    assertEquals(emptyMap(), defaultSetting.value);
    assertEquals(DEFAULT, defaultSetting.origin);

    // ENV ordered map should be reported
    ConfigSetting envSetting = collected.get(ENV).get("test.ordered.map");
    assertEquals(
        mapOf("first", "env_first", "second", "env_second", "third", "env_third"),
        envSetting.value);
    assertEquals(ENV, envSetting.origin);

    // JVM ordered map should be reported
    ConfigSetting jvmSetting = collected.get(JVM_PROP).get("test.ordered.map");
    assertEquals(
        mapOf("second", "jvm_second", "fourth", "jvm_fourth", "first", "jvm_first"),
        jvmSetting.value);
    assertEquals(JVM_PROP, jvmSetting.origin);

    // Final calculated result should be reported with highest seqId
    ConfigSetting calculatedSetting = collected.get(CALCULATED).get("test.ordered.map");
    assertEquals(result, calculatedSetting.value);
    assertEquals(CALCULATED, calculatedSetting.origin);

    // Calculated should have highest seqId
    assertEquals(
        maxSeqId(defaultSetting, envSetting, jvmSetting, calculatedSetting),
        calculatedSetting.seqId);
  }

  @Test
  void
      configProviderMethodsThatCallGetStringInternallyReportTheirOwnDefaultsBeforeGetStringsNullDefault() {
    ConfigCollector.get().collect(); // clear previous state
    // No environment or system property values set, so methods should fall back to their defaults
    ConfigProvider provider = ConfigProvider.createDefault();
    List<String> defaultList = asList("default", "list");
    Set<String> defaultSet = new HashSet<>(asList("default", "set"));

    ConfigOrigin enumResult = provider.getEnum("test.enum", ConfigOrigin.class, CODE);
    List<String> listResult = provider.getList("test.list", defaultList);
    Set<String> setResult = provider.getSet("test.set", defaultSet);
    BitSet rangeResult = provider.getIntegerRange("test.range", new BitSet());
    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    // Each method should have reported its own default, not getString's null default

    ConfigSetting enumDefault = collected.get(DEFAULT).get("test.enum");
    assertEquals("CODE", enumDefault.stringValue()); // ConfigOrigin.CODE.name()
    assertEquals(DEFAULT, enumDefault.origin);
    assertEquals(DEFAULT_SEQ_ID, enumDefault.seqId);

    ConfigSetting listDefault = collected.get(DEFAULT).get("test.list");
    assertEquals(defaultList, listDefault.value);
    assertEquals(DEFAULT, listDefault.origin);
    assertEquals(DEFAULT_SEQ_ID, listDefault.seqId);

    ConfigSetting setDefault = collected.get(DEFAULT).get("test.set");
    assertEquals(defaultSet, setDefault.value);
    assertEquals(DEFAULT, setDefault.origin);
    assertEquals(DEFAULT_SEQ_ID, setDefault.seqId);

    ConfigSetting rangeDefault = collected.get(DEFAULT).get("test.range");
    assertEquals(new BitSet(), rangeDefault.value);
    assertEquals(DEFAULT, rangeDefault.origin);
    assertEquals(DEFAULT_SEQ_ID, rangeDefault.seqId);

    // Verify the methods returned their default values (not null)
    assertEquals(CODE, enumResult);
    assertEquals(defaultList, listResult);
    assertEquals(defaultSet, setResult);
    assertEquals(new BitSet(), rangeResult);
  }

  // NOTE: This is a case that SHOULD never occur. #reReportToCollector(String, int) should only be
  // called with valid origins
  @Test
  void configValueResolverReReportToCollectorHandlesNullOriginGracefully() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider.ConfigValueResolver<String> resolver =
        ConfigProvider.ConfigValueResolver.of("1");

    resolver.reReportToCollector("test.key", 5);

    for (Map<String, ConfigSetting> settings : ConfigCollector.get().collect().values()) {
      assertFalse(settings.containsKey("test.key"));
    }
  }

  @Test
  void configMergeResolverReportsCorrectOriginForSingleVsMultipleSourceContributions() {
    ConfigCollector.get().collect(); // clear previous state
    ConfigProvider provider = ConfigProvider.createDefault();

    // when: Only ENV source contributes to merged map
    injectEnvConfig("DD_SINGLE_SOURCE_MAP", "key1:value1,key2:value2");
    // No JVM prop set, so only ENV contributes
    Map<String, String> singleSourceResult = provider.getMergedMap("single.source.map");
    Map<ConfigOrigin, Map<String, ConfigSetting>> singleSourceCollected =
        ConfigCollector.get().collect();

    // then: Should report with ENV origin, not CALCULATED
    assertEquals(mapOf("key1", "value1", "key2", "value2"), singleSourceResult);

    // Should have DEFAULT for default value
    ConfigSetting singleDefault = singleSourceCollected.get(DEFAULT).get("single.source.map");
    assertEquals(emptyMap(), singleDefault.value);

    // Should have ENV for the actual value (not CALCULATED)
    ConfigSetting singleEnv = singleSourceCollected.get(ENV).get("single.source.map");
    assertEquals(mapOf("key1", "value1", "key2", "value2"), singleEnv.value);
    assertEquals(ENV, singleEnv.origin);

    // Should NOT have CALCULATED entry since only one source contributed
    Map<String, ConfigSetting> singleCalculated = singleSourceCollected.get(CALCULATED);
    if (singleCalculated != null) {
      assertNull(singleCalculated.get("single.source.map"));
    }

    // when: Multiple sources contribute to merged map
    ConfigCollector.get().collect(); // clear for next test
    injectEnvConfig("DD_MULTI_SOURCE_MAP", "env_key:env_value,shared:from_env");
    injectSysConfig("multi.source.map", "jvm_key:jvm_value,shared:from_jvm");
    Map<String, String> multiSourceResult = provider.getMergedMap("multi.source.map");
    Map<ConfigOrigin, Map<String, ConfigSetting>> multiSourceCollected =
        ConfigCollector.get().collect();

    // then: Should report with CALCULATED origin when multiple sources contribute
    assertEquals(
        mapOf("env_key", "env_value", "jvm_key", "jvm_value", "shared", "from_jvm"),
        multiSourceResult);

    // Should have CALCULATED for the final merged result
    ConfigSetting multiCalculated = multiSourceCollected.get(CALCULATED).get("multi.source.map");
    assertEquals(multiSourceResult, multiCalculated.value);
    assertEquals(CALCULATED, multiCalculated.origin);
  }

  private static Map<String, String> mapOf(String... keyValues) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < keyValues.length; i += 2) {
      map.put(keyValues[i], keyValues[i + 1]);
    }
    return map;
  }

  private static int maxSeqId(ConfigSetting... settings) {
    return highestSeqIdSetting(settings).seqId;
  }

  private static ConfigSetting highestSeqIdSetting(ConfigSetting... settings) {
    ConfigSetting highest = settings[0];
    for (ConfigSetting setting : settings) {
      if (setting.seqId > highest.seqId) {
        highest = setting;
      }
    }
    return highest;
  }

  @FunctionalInterface
  interface TypedGetter {
    Object get(ConfigProvider provider, String key, Object defaultValue);
  }
}
