package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_HASH_ALGORITHMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.ConfigOrigin.DEFAULT;
import static datadog.trace.api.ConfigOrigin.ENV;
import static datadog.trace.api.ConfigOrigin.JVM_PROP;
import static datadog.trace.api.ConfigOrigin.REMOTE;
import static datadog.trace.api.ConfigSetting.ABSENT_SEQ_ID;
import static datadog.trace.api.ConfigSetting.DEFAULT_SEQ_ID;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT;
import static datadog.trace.api.config.CiVisibilityConfig.CIVISIBILITY_GRADLE_SOURCE_SETS;
import static datadog.trace.api.config.GeneralConfig.APPLICATION_KEY;
import static datadog.trace.api.config.GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.config.IastConfig.IAST_TELEMETRY_VERBOSITY;
import static datadog.trace.api.config.IastConfig.IAST_WEAK_HASH_ALGORITHMS;
import static datadog.trace.api.config.JmxFetchConfig.JMX_FETCH_CHECK_PERIOD;
import static datadog.trace.api.config.TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS;
import static datadog.trace.api.config.TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES;
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.PROXY_NO_PROXY;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_CLIENT_ERROR_STATUSES;
import static datadog.trace.api.config.TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_PEER_SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA;
import static datadog.trace.api.naming.SpanNaming.SCHEMA_MIN_VERSION;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.util.ConfigStrings.toEnvVar;
import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class ConfigCollectorTest {

  private static final String TEST_KEY = "test.key";
  private static final String TEST_VALUE = "test-value";

  @TableTest({
    "scenario                                        | configKey                                                  | configValue                                   ",
    "ConfigProvider.getEnum                          | IastConfig.IAST_TELEMETRY_VERBOSITY                        | DEBUG                                         ",
    "ConfigProvider.getString                        | TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA                   | v1                                            ",
    "ConfigProvider.getStringNotEmpty                | AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING         | extended                                      ",
    "ConfigProvider.getStringExcludingSource         | DDTags.SERVICE                                             | my-service                                    ",
    "ConfigProvider.getBoolean                       | TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES         | true                                          ",
    "ConfigProvider.getInteger                       | JmxFetchConfig.JMX_FETCH_CHECK_PERIOD                      | 60                                            ",
    "ConfigProvider.getLong                          | CiVisibilityConfig.CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS | 450273                                        ",
    "ConfigProvider.getFloat                         | GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL                 | 1.5                                           ",
    "ConfigProvider.getDouble                        | TracerConfig.TRACE_SAMPLE_RATE                             | 2.2                                           ",
    "ConfigProvider.getList                          | TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS | 'someTopic,otherTopic'                        ",
    "ConfigProvider.getSet                           | IastConfig.IAST_WEAK_HASH_ALGORITHMS                       | 'SHA1,SHA-1'                                  ",
    "ConfigProvider.getSpacedList                    | TracerConfig.PROXY_NO_PROXY                                | 'a b c'                                       ",
    "ConfigProvider.getMergedMap                     | TracerConfig.TRACE_PEER_SERVICE_MAPPING                    | 'service1:best_service,userService:my_service'",
    "ConfigProvider.getOrderedMap                    | TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING  | '/asdf/*:/test'                               ",
    "ConfigProvider.getMergedMapWithOptionalMappings | TracerConfig.HEADER_TAGS                                   | 'e:five'                                      ",
    "ConfigProvider.getIntegerRange                  | TracerConfig.TRACE_HTTP_CLIENT_ERROR_STATUSES              | '400-402'                                     "
  })
  void nonDefaultConfigSettingsGetCollected(
      @ConvertWith(ConfigKeyConverter.class) String configKey, String configValue) {
    injectEnvConfig(toEnvVar(configKey), configValue);

    Map<String, ConfigSetting> envConfigByKey = ConfigCollector.get().collect().get(ENV);
    ConfigSetting config = envConfigByKey.get(configKey);
    assertEquals(configValue, config.stringValue());
    assertEquals(ENV, config.origin);
  }

  @TableTest({
    "scenario                                           | configKey                                                 | envConfigValue                                 | jvmConfigValue           ",
    "ConfigProvider.getMergedMap                        | TracerConfig.TRACE_PEER_SERVICE_MAPPING                   | 'service1:best_service,userService:my_service' | 'service2:backup_service'",
    "ConfigProvider.getOrderedMap                       | TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING | '/asdf/*:/test,/b:some'                        | '/a:prop'                ",
    "ConfigProvider.getMergedMapWithOptionalMappings    | TracerConfig.HEADER_TAGS                                  | 'j:ten'                                        | 'e:five,b:six'           ",
    "'ConfigProvider.getMergedMap, but only one source' | TracerConfig.TRACE_PEER_SERVICE_MAPPING                   | 'service1:best_service,userService:my_service' |                          "
  })
  void shouldCollectMergedDataFromMultipleSources(
      @ConvertWith(ConfigKeyConverter.class) String configKey,
      String envConfigValue,
      String jvmConfigValue) {
    injectEnvConfig(toEnvVar(configKey), envConfigValue);
    if (jvmConfigValue != null) {
      injectSysConfig(configKey, jvmConfigValue);
    }

    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    ConfigSetting envConfig = collected.get(ENV).get(configKey);
    assertEquals(envConfigValue, envConfig.stringValue());
    assertEquals(ENV, envConfig.origin);
    if (jvmConfigValue != null) {
      ConfigSetting jvmConfig = collected.get(JVM_PROP).get(configKey);
      assertEquals(
          new HashSet<>(asList(jvmConfigValue.split(","))),
          new HashSet<>(asList(jvmConfig.stringValue().split(","))));
      assertEquals(JVM_PROP, jvmConfig.origin);
    }

    // TODO: Add a check for which setting the collector recognizes as highest precedence
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("defaultNotNullConfigSettingsAreCollectedArguments")
  void defaultNotNullConfigSettingsAreCollected(String configKey, String defaultValue) {
    Map<String, ConfigSetting> defaultConfigByKey = ConfigCollector.get().collect().get(DEFAULT);
    ConfigSetting setting = defaultConfigByKey.get(configKey);
    assertEquals(DEFAULT, setting.origin);
    assertEquals(defaultValue, setting.stringValue());
  }

  static Stream<Arguments> defaultNotNullConfigSettingsAreCollectedArguments() {
    return Stream.of(
        arguments(IAST_TELEMETRY_VERBOSITY, Verbosity.INFORMATION.toString()),
        arguments(TRACE_SPAN_ATTRIBUTE_SCHEMA, "v" + SCHEMA_MIN_VERSION),
        arguments(
            TELEMETRY_HEARTBEAT_INTERVAL,
            Float.valueOf(DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL).toString()),
        arguments(CIVISIBILITY_GRADLE_SOURCE_SETS, "main,test"),
        arguments(IAST_WEAK_HASH_ALGORITHMS, String.join(",", DEFAULT_IAST_WEAK_HASH_ALGORITHMS)),
        arguments(TRACE_HTTP_CLIENT_ERROR_STATUSES, "400-500"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        APPLICATION_KEY,
        RESOLVER_USE_URL_CACHES,
        JMX_FETCH_CHECK_PERIOD,
        CIVISIBILITY_DEBUG_PORT,
        TRACE_SAMPLE_RATE,
        JMS_PROPAGATION_DISABLED_TOPICS,
        PROXY_NO_PROXY
      })
  void defaultNullConfigSettingsAreAlsoCollected(String configKey) {
    Map<String, ConfigSetting> defaultConfigByKey = ConfigCollector.get().collect().get(DEFAULT);
    ConfigSetting setting = defaultConfigByKey.get(configKey);

    assertNull(setting.stringValue());
    assertEquals(DEFAULT, setting.origin);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        TRACE_PEER_SERVICE_MAPPING,
        TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING,
        HEADER_TAGS
      })
  void defaultEmptyMapsAndListConfigSettingsAreCollectedAsEmptyStrings(String configKey) {
    Map<String, ConfigSetting> defaultConfigByKey = ConfigCollector.get().collect().get(DEFAULT);
    ConfigSetting setting = defaultConfigByKey.get(configKey);

    assertEquals("", setting.stringValue());
    assertEquals(DEFAULT, setting.origin);
  }

  @Test
  void putGetConfigurations() {
    ConfigCollector.get().collect();

    ConfigCollector.get().put("key1", "value1", DEFAULT, ABSENT_SEQ_ID);
    ConfigCollector.get().put("key2", "value2", ENV, ABSENT_SEQ_ID);
    ConfigCollector.get().put("key1", "value4", REMOTE, ABSENT_SEQ_ID);
    ConfigCollector.get().put("key3", "value3", JVM_PROP, ABSENT_SEQ_ID);

    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();
    assertEquals(ConfigSetting.of("key1", "value4", REMOTE), collected.get(REMOTE).get("key1"));
    assertEquals(ConfigSetting.of("key2", "value2", ENV), collected.get(ENV).get("key2"));
    assertEquals(ConfigSetting.of("key3", "value3", JVM_PROP), collected.get(JVM_PROP).get("key3"));
    assertEquals(ConfigSetting.of("key1", "value1", DEFAULT), collected.get(DEFAULT).get("key1"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"api-key", "DD_PROFILING_API_KEY", "DD_PROFILING_APIKEY"})
  void hidePiiConfigurationData(String configKey) {
    ConfigCollector.get().collect();

    ConfigCollector.get().put(configKey, "sensitive data", ENV, ABSENT_SEQ_ID);

    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();
    assertEquals("<hidden>", collected.get(ENV).get(configKey).stringValue());
  }

  @TableTest({
    "scenario               | key                    | value   ",
    "trace enabled          | trace.enabled          | true    ",
    "profiling enabled      | profiling.enabled      | false   ",
    "appsec enabled         | appsec.enabled         | inactive",
    "data streams enabled   | data.streams.enabled   | false   ",
    "trace tags             | trace.tags             | ''      ",
    "trace header tags      | trace.header.tags      | ''      ",
    "logs injection enabled | logs.injection.enabled | true    ",
    // defaults to null meaning sample everything but not exactly the same as when explicitly set
    // to 1.0
    "trace sample rate      | trace.sample.rate      |         "
  })
  void collectsCommonSettingDefaultValues(String key, String value) {
    Map<String, ConfigSetting> defaultConfigByKey = ConfigCollector.get().collect().get(DEFAULT);

    ConfigSetting setting = defaultConfigByKey.get(key);
    assertEquals(value, setting.stringValue());
    assertEquals(DEFAULT, setting.origin);
  }

  @TableTest({
    "scenario               | key                    | value                                                    ",
    "trace enabled          | trace.enabled          | false                                                    ",
    "profiling enabled      | profiling.enabled      | true                                                     ",
    "appsec enabled         | appsec.enabled         | false                                                    ",
    "data streams enabled   | data.streams.enabled   | true                                                     ",
    // doesn't preserve ordering for some maps
    "trace tags             | trace.tags             | 'component:web,team:apm'                                 ",
    // lowercase keys for some maps merged from different sources
    "trace header tags      | trace.header.tags      | 'x-header-tag-1:header_tag_1,x-header-tag-2:header_tag_2'",
    "logs injection enabled | logs.injection.enabled | false                                                    ",
    "trace sample rate      | trace.sample.rate      | 0.3                                                      "
  })
  @WithConfig(key = "DD_TRACE_ENABLED", value = "false", env = true, addPrefix = false)
  @WithConfig(key = "DD_PROFILING_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "DD_APPSEC_ENABLED", value = "false", env = true, addPrefix = false)
  @WithConfig(key = "DD_DATA_STREAMS_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "DD_TAGS", value = "team:apm,component:web", env = true, addPrefix = false)
  @WithConfig(
      key = "DD_TRACE_HEADER_TAGS",
      value = "X-Header-Tag-1:header_tag_1,X-Header-Tag-2:header_tag_2",
      env = true,
      addPrefix = false)
  @WithConfig(key = "DD_LOGS_INJECTION", value = "false", env = true, addPrefix = false)
  @WithConfig(key = "DD_TRACE_SAMPLE_RATE", value = "0.3", env = true, addPrefix = false)
  void collectsCommonSettingOverriddenValues(String key, String value) {
    Map<String, ConfigSetting> envConfigByKey = ConfigCollector.get().collect().get(ENV);

    ConfigSetting setting = envConfigByKey.get(key);
    assertEquals(value, setting.stringValue());
    assertEquals(ENV, setting.origin);
  }

  @Test
  void configCollectorCreatesConfigSettingsWithCorrectSeqId() {
    ConfigCollector.get().collect(); // clear previous state

    // Simulate sources with increasing precedence and a default
    ConfigCollector.get().put(TEST_KEY, "default", DEFAULT, DEFAULT_SEQ_ID);
    ConfigCollector.get().put(TEST_KEY, "env", ENV, 2);
    ConfigCollector.get().put(TEST_KEY, "jvm", JVM_PROP, 3);
    ConfigCollector.get().put(TEST_KEY, "remote", REMOTE, 4);

    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();
    ConfigSetting defaultSetting = collected.get(DEFAULT).get(TEST_KEY);
    ConfigSetting envSetting = collected.get(ENV).get(TEST_KEY);
    ConfigSetting jvmSetting = collected.get(JVM_PROP).get(TEST_KEY);
    ConfigSetting remoteSetting = collected.get(REMOTE).get(TEST_KEY);

    assertEquals(DEFAULT_SEQ_ID, defaultSetting.seqId);
    // Higher precedence = higher seqId
    assertTrue(defaultSetting.seqId < envSetting.seqId);
    assertTrue(envSetting.seqId < jvmSetting.seqId);
    assertTrue(jvmSetting.seqId < remoteSetting.seqId);
  }

  @Test
  @WithConfig(key = TEST_KEY, value = TEST_VALUE)
  void configIdIsNullForNonStableConfigSource() {
    StrictnessPolicy strictness = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(StrictnessPolicy.TEST);
    try {
      // Trigger config collection by getting a value
      ConfigProvider.getInstance().getString(TEST_KEY);
      Map<ConfigOrigin, Map<String, ConfigSetting>> settings = ConfigCollector.get().collect();

      // Verify the config was collected but without a config ID
      ConfigSetting setting = settings.get(JVM_PROP).get(TEST_KEY);
      assertNotNull(setting);
      assertNull(setting.configId);
      assertEquals(TEST_VALUE, setting.value);
      assertEquals(JVM_PROP, setting.origin);
    } finally {
      ConfigHelper.get().setConfigInversionStrict(strictness);
    }
  }

  @Test
  void defaultSourcesCannotBeOverridden() {
    String overrideValue = "override-value";

    // Need to make 2 calls in a row because collect() will empty the map
    ConfigCollector.get().putDefault(TEST_KEY, TEST_VALUE);
    ConfigCollector.get().putDefault(TEST_KEY, overrideValue);
    Map<String, ConfigSetting> defaultConfigByKey = ConfigCollector.get().collect().get(DEFAULT);
    ConfigSetting setting = defaultConfigByKey.get(TEST_KEY);

    assertEquals(TEST_VALUE, setting.stringValue());
    assertEquals(DEFAULT, setting.origin);
    assertEquals(DEFAULT_SEQ_ID, setting.seqId);
  }

  /**
   * Resolves {@code ClassName.CONSTANT} cells to the config key constant they name, looked up in
   * {@code datadog.trace.api.config} then {@code datadog.trace.api}.
   */
  static final class ConfigKeyConverter implements ArgumentConverter {
    private static final String[] PACKAGES = {"datadog.trace.api.config.", "datadog.trace.api."};

    @Override
    public Object convert(Object source, ParameterContext context) {
      String constant = (String) source;
      int dot = constant.indexOf('.');
      String className = constant.substring(0, dot);
      String fieldName = constant.substring(dot + 1);
      for (String pkg : PACKAGES) {
        try {
          Field field = Class.forName(pkg + className).getDeclaredField(fieldName);
          field.setAccessible(true);
          return field.get(null);
        } catch (ReflectiveOperationException ignored) {
          // try next package
        }
      }
      throw new ArgumentConversionException("Unknown config key constant " + constant);
    }
  }
}
