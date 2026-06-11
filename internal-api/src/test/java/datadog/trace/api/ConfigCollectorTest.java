package datadog.trace.api;

import static datadog.trace.api.ConfigDefaults.DEFAULT_IAST_WEAK_HASH_ALGORITHMS;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL;
import static datadog.trace.api.ConfigSetting.ABSENT_SEQ_ID;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static datadog.trace.junit.utils.config.WithConfigExtension.injectSysConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.api.config.AppSecConfig;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.IastConfig;
import datadog.trace.api.config.JmxFetchConfig;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.api.iast.telemetry.Verbosity;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.util.ConfigStrings;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

public class ConfigCollectorTest extends DDJavaSpecification {

  static Stream<Arguments> nonDefaultConfigSettingsGetCollectedArguments() {
    // expectedValue equals configValue for every setting except those redacted from configuration
    // telemetry (e.g. the application key), where the collected value is rendered as "<hidden>".
    return Stream.of(
        // ConfigProvider.getEnum
        arguments(IastConfig.IAST_TELEMETRY_VERBOSITY, Verbosity.DEBUG.toString(), null),
        // ConfigProvider.getString
        arguments(TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, "v1", null),
        // ConfigProvider.getStringNotEmpty
        arguments(
            AppSecConfig.APPSEC_AUTOMATED_USER_EVENTS_TRACKING,
            UserEventTrackingMode.EXTENDED.toString(),
            null),
        // ConfigProvider.getStringExcludingSource
        arguments(DDTags.SERVICE, "my-service", null),
        // ConfigProvider.getStringExcludingSource, redacted from configuration telemetry
        arguments(GeneralConfig.APPLICATION_KEY, "app-key", "<hidden>"),
        arguments(GeneralConfig.API_KEY, "some-api-key", "<hidden>"),
        // ConfigProvider.getString, redacted from configuration telemetry
        arguments(ProfilingConfig.PROFILING_PROXY_PASSWORD, "some-proxy-password", "<hidden>"),
        // ConfigProvider.getBoolean
        arguments(TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES, "true", null),
        // ConfigProvider.getInteger
        arguments(JmxFetchConfig.JMX_FETCH_CHECK_PERIOD, "60", null),
        // ConfigProvider.getLong
        arguments(CiVisibilityConfig.CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS, "450273", null),
        // ConfigProvider.getFloat
        arguments(GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL, "1.5", null),
        // ConfigProvider.getDouble
        arguments(TracerConfig.TRACE_SAMPLE_RATE, "2.2", null),
        // ConfigProvider.getList
        arguments(
            TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS,
            "someTopic,otherTopic",
            null),
        // ConfigProvider.getSet
        arguments(IastConfig.IAST_WEAK_HASH_ALGORITHMS, "SHA1,SHA-1", null),
        // ConfigProvider.getSpacedList
        arguments(TracerConfig.PROXY_NO_PROXY, "a b c", null),
        // ConfigProvider.getMergedMap
        arguments(
            TracerConfig.TRACE_PEER_SERVICE_MAPPING,
            "service1:best_service,userService:my_service",
            null),
        // ConfigProvider.getOrderedMap
        arguments(TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING, "/asdf/*:/test", null),
        // ConfigProvider.getMergedMapWithOptionalMappings
        arguments(TracerConfig.HEADER_TAGS, "e:five", null),
        // ConfigProvider.getIntegerRange
        arguments(TracerConfig.TRACE_HTTP_CLIENT_ERROR_STATUSES, "400-402", null));
  }

  @ParameterizedTest
  @MethodSource("nonDefaultConfigSettingsGetCollectedArguments")
  void nonDefaultConfigSettingsGetCollected(
      String configKey, String configValue, String expectedOverride) {
    // expectedValue equals configValue unless an explicit override is provided (used for redacted
    // settings rendered as "<hidden>").
    String expectedValue = expectedOverride != null ? expectedOverride : configValue;
    injectEnvConfig(ConfigStrings.toEnvVar(configKey), configValue);

    Map<String, ConfigSetting> envConfigByKey =
        ConfigCollector.get().collect().get(ConfigOrigin.ENV);
    ConfigSetting config = envConfigByKey.get(configKey);
    assertEquals(expectedValue, config.stringValue());
    assertEquals(ConfigOrigin.ENV, config.origin);
  }

  static Stream<Arguments> shouldCollectMergedDataFromMultipleSourcesArguments() {
    return Stream.of(
        // ConfigProvider.getMergedMap
        arguments(
            TracerConfig.TRACE_PEER_SERVICE_MAPPING,
            "service1:best_service,userService:my_service",
            "service2:backup_service"),
        // ConfigProvider.getOrderedMap
        arguments(
            TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING,
            "/asdf/*:/test,/b:some",
            "/a:prop"),
        // ConfigProvider.getMergedMapWithOptionalMappings
        arguments(TracerConfig.HEADER_TAGS, "j:ten", "e:five,b:six"),
        // ConfigProvider.getMergedMap, but only one source
        arguments(
            TracerConfig.TRACE_PEER_SERVICE_MAPPING,
            "service1:best_service,userService:my_service",
            null));
  }

  @ParameterizedTest
  @MethodSource("shouldCollectMergedDataFromMultipleSourcesArguments")
  void shouldCollectMergedDataFromMultipleSources(
      String configKey, String envConfigValue, String jvmConfigValue) {
    injectEnvConfig(ConfigStrings.toEnvVar(configKey), envConfigValue);
    if (jvmConfigValue != null) {
      injectSysConfig(configKey, jvmConfigValue);
    }

    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();

    Map<String, ConfigSetting> envSetting = collected.get(ConfigOrigin.ENV);
    ConfigSetting envConfig = envSetting.get(configKey);
    assertEquals(envConfigValue, envConfig.stringValue());
    assertEquals(ConfigOrigin.ENV, envConfig.origin);
    if (jvmConfigValue != null) {
      Map<String, ConfigSetting> jvmSetting = collected.get(ConfigOrigin.JVM_PROP);
      ConfigSetting jvmConfig = jvmSetting.get(configKey);
      assertEquals(
          new HashSet<>(Arrays.asList(jvmConfigValue.split(","))),
          new HashSet<>(Arrays.asList(jvmConfig.stringValue().split(","))));
      assertEquals(ConfigOrigin.JVM_PROP, jvmConfig.origin);
    }

    // TODO: Add a check for which setting the collector recognizes as highest precedence
  }

  static Stream<Arguments> defaultNotNullConfigSettingsAreCollectedArguments() {
    return Stream.of(
        arguments(IastConfig.IAST_TELEMETRY_VERBOSITY, Verbosity.INFORMATION.toString()),
        arguments(TracerConfig.TRACE_SPAN_ATTRIBUTE_SCHEMA, "v" + SpanNaming.SCHEMA_MIN_VERSION),
        arguments(
            GeneralConfig.TELEMETRY_HEARTBEAT_INTERVAL,
            Float.toString(DEFAULT_TELEMETRY_HEARTBEAT_INTERVAL)),
        arguments(CiVisibilityConfig.CIVISIBILITY_GRADLE_SOURCE_SETS, "main,test"),
        arguments(
            IastConfig.IAST_WEAK_HASH_ALGORITHMS,
            String.join(",", DEFAULT_IAST_WEAK_HASH_ALGORITHMS)),
        arguments(TracerConfig.TRACE_HTTP_CLIENT_ERROR_STATUSES, "400-500"));
  }

  @ParameterizedTest
  @MethodSource("defaultNotNullConfigSettingsAreCollectedArguments")
  void defaultNotNullConfigSettingsAreCollected(String configKey, String defaultValue) {
    Map<String, ConfigSetting> defaultConfigByKey =
        ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT);
    ConfigSetting setting = defaultConfigByKey.get(configKey);
    assertEquals(ConfigOrigin.DEFAULT, setting.origin);
    assertEquals(defaultValue, setting.stringValue());
  }

  static Stream<Arguments> defaultNullConfigSettingsAreAlsoCollectedArguments() {
    // GeneralConfig.APPLICATION_KEY is redacted from configuration telemetry, so its collected
    // value is rendered as "<hidden>" rather than null; that redaction is verified in the
    // nonDefaultConfigSettingsGetCollected test above.
    return Stream.of(
        arguments(TraceInstrumentationConfig.RESOLVER_USE_URL_CACHES),
        arguments(JmxFetchConfig.JMX_FETCH_CHECK_PERIOD),
        arguments(CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT),
        arguments(TracerConfig.TRACE_SAMPLE_RATE),
        arguments(TraceInstrumentationConfig.JMS_PROPAGATION_DISABLED_TOPICS),
        arguments(TracerConfig.PROXY_NO_PROXY));
  }

  @ParameterizedTest
  @MethodSource("defaultNullConfigSettingsAreAlsoCollectedArguments")
  void defaultNullConfigSettingsAreAlsoCollected(String configKey) {
    Map<String, ConfigSetting> defaultConfigByKey =
        ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT);
    ConfigSetting cs = defaultConfigByKey.get(configKey);

    assertEquals(configKey, cs.key);
    assertNull(cs.stringValue());
    assertEquals(ConfigOrigin.DEFAULT, cs.origin);
  }

  static Stream<Arguments> defaultEmptyMapsAndListConfigSettingsArguments() {
    return Stream.of(
        arguments(TracerConfig.TRACE_PEER_SERVICE_MAPPING),
        arguments(TracerConfig.TRACE_HTTP_SERVER_PATH_RESOURCE_NAME_MAPPING),
        arguments(TracerConfig.HEADER_TAGS));
  }

  @ParameterizedTest
  @MethodSource("defaultEmptyMapsAndListConfigSettingsArguments")
  void defaultEmptyMapsAndListConfigSettingsAreCollectedAsEmptyStrings(String configKey) {
    Map<String, ConfigSetting> defaultConfigByKey =
        ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT);
    ConfigSetting cs = defaultConfigByKey.get(configKey);

    assertEquals(configKey, cs.key);
    assertEquals("", cs.stringValue());
    assertEquals(ConfigOrigin.DEFAULT, cs.origin);
  }

  @Test
  void putGetConfigurations() {
    ConfigCollector.get().collect();

    ConfigCollector.get().put("key1", "value1", ConfigOrigin.DEFAULT, ABSENT_SEQ_ID);
    ConfigCollector.get().put("key2", "value2", ConfigOrigin.ENV, ABSENT_SEQ_ID);
    ConfigCollector.get().put("key1", "value4", ConfigOrigin.REMOTE, ABSENT_SEQ_ID);
    ConfigCollector.get().put("key3", "value3", ConfigOrigin.JVM_PROP, ABSENT_SEQ_ID);

    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();
    assertEquals(
        ConfigSetting.of("key1", "value4", ConfigOrigin.REMOTE),
        collected.get(ConfigOrigin.REMOTE).get("key1"));
    assertEquals(
        ConfigSetting.of("key2", "value2", ConfigOrigin.ENV),
        collected.get(ConfigOrigin.ENV).get("key2"));
    assertEquals(
        ConfigSetting.of("key3", "value3", ConfigOrigin.JVM_PROP),
        collected.get(ConfigOrigin.JVM_PROP).get("key3"));
    assertEquals(
        ConfigSetting.of("key1", "value1", ConfigOrigin.DEFAULT),
        collected.get(ConfigOrigin.DEFAULT).get("key1"));
  }

  @Test
  void redactsSensitiveConfigurationValues() {
    ConfigCollector.get().collect();

    ConfigCollector.get().put("api-key", "somevalue", ConfigOrigin.ENV, ABSENT_SEQ_ID);

    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();
    assertEquals("<hidden>", collected.get(ConfigOrigin.ENV).get("api-key").stringValue());
  }

  @TableTest({
    "scenario          | key                    | value   ",
    "trace enabled     | trace.enabled          | true    ",
    "profiling enabled | profiling.enabled      | false   ",
    "appsec enabled    | appsec.enabled         | inactive",
    "data streams      | data.streams.enabled   | false   ",
    "trace tags        | trace.tags             | ''      ",
    "trace header tags | trace.header.tags      | ''      ",
    "logs injection    | logs.injection.enabled | true    ",
    "trace sample rate | trace.sample.rate      |         "
  })
  void collectsCommonSettingDefaultValues(String key, String value) {
    Map<String, ConfigSetting> defaultConfigByKey =
        ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT);

    ConfigSetting setting = defaultConfigByKey.get(key);
    assertEquals(key, setting.key);
    assertEquals(value, setting.stringValue());
    assertEquals(ConfigOrigin.DEFAULT, setting.origin);
  }

  @TableTest({
    "scenario          | key                    | value                                                  ",
    "trace enabled     | trace.enabled          | false                                                  ",
    "profiling enabled | profiling.enabled      | true                                                   ",
    "appsec enabled    | appsec.enabled         | false                                                  ",
    "data streams      | data.streams.enabled   | true                                                   ",
    "trace tags        | trace.tags             | component:web,team:apm                                 ",
    "trace header tags | trace.header.tags      | x-header-tag-1:header_tag_1,x-header-tag-2:header_tag_2",
    "logs injection    | logs.injection.enabled | false                                                  ",
    "trace sample rate | trace.sample.rate      | 0.3                                                    "
  })
  void collectsCommonSettingOverriddenValues(String key, String value) {
    injectEnvConfig("DD_TRACE_ENABLED", "false");
    injectEnvConfig("DD_PROFILING_ENABLED", "true");
    injectEnvConfig("DD_APPSEC_ENABLED", "false");
    injectEnvConfig("DD_DATA_STREAMS_ENABLED", "true");
    injectEnvConfig("DD_TAGS", "team:apm,component:web");
    injectEnvConfig(
        "DD_TRACE_HEADER_TAGS", "X-Header-Tag-1:header_tag_1,X-Header-Tag-2:header_tag_2");
    injectEnvConfig("DD_LOGS_INJECTION", "false");
    injectEnvConfig("DD_TRACE_SAMPLE_RATE", "0.3");

    Map<String, ConfigSetting> envConfigByKey =
        ConfigCollector.get().collect().get(ConfigOrigin.ENV);

    ConfigSetting setting = envConfigByKey.get(key);
    assertEquals(key, setting.key);
    assertEquals(value, setting.stringValue());
    assertEquals(ConfigOrigin.ENV, setting.origin);
  }

  @Test
  void configCollectorCreatesConfigSettingsWithCorrectSeqId() {
    ConfigCollector.get().collect(); // clear previous state

    // Simulate sources with increasing precedence and a default
    ConfigCollector.get()
        .put("test.key", "default", ConfigOrigin.DEFAULT, ConfigSetting.DEFAULT_SEQ_ID);
    ConfigCollector.get().put("test.key", "env", ConfigOrigin.ENV, 2);
    ConfigCollector.get().put("test.key", "jvm", ConfigOrigin.JVM_PROP, 3);
    ConfigCollector.get().put("test.key", "remote", ConfigOrigin.REMOTE, 4);

    Map<ConfigOrigin, Map<String, ConfigSetting>> collected = ConfigCollector.get().collect();
    ConfigSetting defaultSetting = collected.get(ConfigOrigin.DEFAULT).get("test.key");
    ConfigSetting envSetting = collected.get(ConfigOrigin.ENV).get("test.key");
    ConfigSetting jvmSetting = collected.get(ConfigOrigin.JVM_PROP).get("test.key");
    ConfigSetting remoteSetting = collected.get(ConfigOrigin.REMOTE).get("test.key");

    assertEquals(ConfigSetting.DEFAULT_SEQ_ID, defaultSetting.seqId);
    // Higher precedence = higher seqId
    assertTrue(defaultSetting.seqId < envSetting.seqId);
    assertTrue(envSetting.seqId < jvmSetting.seqId);
    assertTrue(jvmSetting.seqId < remoteSetting.seqId);
  }

  @Test
  void configIdIsNullForNonStableConfigSource() {
    ConfigHelper.StrictnessPolicy strictness = ConfigHelper.get().configInversionStrictFlag();
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST);

    String key = "test.key";
    String value = "test-value";
    injectSysConfig(key, value);

    try {
      // Trigger config collection by getting a value
      ConfigProvider.getInstance().getString(key);
      Map<ConfigOrigin, Map<String, ConfigSetting>> settings = ConfigCollector.get().collect();

      // Verify the config was collected but without a config ID
      ConfigSetting setting = settings.get(ConfigOrigin.JVM_PROP).get(key);
      assertNotNull(setting);
      assertNull(setting.configId);
      assertEquals(value, setting.value);
      assertEquals(ConfigOrigin.JVM_PROP, setting.origin);
    } finally {
      ConfigHelper.get().setConfigInversionStrict(strictness);
    }
  }

  @Test
  void defaultSourcesCannotBeOverridden() {
    String key = "test.key";
    String value = "test-value";
    String overrideVal = "override-value";

    // Need to make 2 calls in a row because collect() will empty the map
    ConfigCollector.get().putDefault(key, value);
    ConfigCollector.get().putDefault(key, overrideVal);
    Map<String, ConfigSetting> defaultConfigByKey =
        ConfigCollector.get().collect().get(ConfigOrigin.DEFAULT);
    ConfigSetting cs = defaultConfigByKey.get(key);

    assertEquals(key, cs.key);
    assertEquals(value, cs.stringValue());
    assertEquals(ConfigOrigin.DEFAULT, cs.origin);
    assertEquals(ConfigSetting.DEFAULT_SEQ_ID, cs.seqId);
  }
}
