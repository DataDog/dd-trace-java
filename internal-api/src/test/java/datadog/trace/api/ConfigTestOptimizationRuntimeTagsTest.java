package datadog.trace.api;

import static datadog.trace.api.config.CiVisibilityConfig.TEST_OPTIMIZATION_RUNTIME_TAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.environment.SystemProperties;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

@ExtendWith(WithConfigExtension.class)
class ConfigTestOptimizationRuntimeTagsTest {

  @Test
  void reportsDetectedTagsByDefault() {
    Config config = Config.get();

    assertEquals(Collections.emptyMap(), config.getTestOptimizationRuntimeTags());
    assertDetectedTags(config.getCiVisibilityWellKnownTags());
  }

  @Test
  void overridesEverySupportedTag() {
    Config config =
        configWithRuntimeTags(
            "{\"os.platform\":\"Linux\",\"os.architecture\":\"amd64\",\"os.version\":\"6.12.0\","
                + "\"runtime.name\":\"OpenJDK Runtime Environment\",\"runtime.version\":\"11.0.0\","
                + "\"runtime.vendor\":\"Eclipse Adoptium\"}");

    CiVisibilityWellKnownTags tags = config.getCiVisibilityWellKnownTags();
    assertEquals("Linux", tags.getOsPlatform().toString());
    assertEquals("amd64", tags.getOsArch().toString());
    assertEquals("6.12.0", tags.getOsVersion().toString());
    assertEquals("OpenJDK Runtime Environment", tags.getRuntimeName().toString());
    assertEquals("11.0.0", tags.getRuntimeVersion().toString());
    assertEquals("Eclipse Adoptium", tags.getRuntimeVendor().toString());
  }

  @Test
  void keepsDetectedValuesForTagsThatAreNotOverridden() {
    Config config = configWithRuntimeTags("{\"os.version\":\"6.12.0\"}");

    CiVisibilityWellKnownTags tags = config.getCiVisibilityWellKnownTags();
    assertEquals("6.12.0", tags.getOsVersion().toString());
    assertEquals(SystemProperties.get("os.name"), tags.getOsPlatform().toString());
    assertEquals(SystemProperties.get("java.version"), tags.getRuntimeVersion().toString());
  }

  @Test
  void trimsValuesAndIgnoresUnsupportedKeysAndUnusableValues() {
    Config config =
        configWithRuntimeTags(
            "{\"os.version\":\" 6.12.0 \",\"runtime.version\":\"\",\"runtime.vendor\":\"  \","
                + "\"runtime.name\":null,\"runtime.architecture\":\"x64\","
                + "\"custom.tag\":\"value\"}");

    Map<String, String> expected = new HashMap<>();
    expected.put("os.version", "6.12.0");
    assertEquals(expected, config.getTestOptimizationRuntimeTags());
  }

  @Test
  void readsTagsFromEnvironmentVariable() {
    WithConfigExtension.injectEnvConfig(
        "DD_TEST_OPTIMIZATION_RUNTIME_TAGS", "{\"os.version\":\"6.12.0\"}", false);

    assertEquals("6.12.0", Config.get().getCiVisibilityWellKnownTags().getOsVersion().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{invalid json}",
        "[\"os.version\"]",
        "\"6.12.0\"",
        "null",
        "{\"os.version\":6}",
        "{\"custom.tag\":[[[[\"nested\"]]]]}"
      })
  void logsWarningAndReportsDetectedTagsForInvalidValue(String value) {
    Logger logger = (Logger) LoggerFactory.getLogger(Config.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      Config config = configWithRuntimeTags(value);

      assertEquals(Collections.emptyMap(), config.getTestOptimizationRuntimeTags());
      assertDetectedTags(config.getCiVisibilityWellKnownTags());
      assertTrue(
          appender.list.stream()
              .anyMatch(event ->
                  event.getLevel() == Level.WARN
                      && event
                          .getFormattedMessage()
                          .contains("DD_TEST_OPTIMIZATION_RUNTIME_TAGS")));
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void rejectsDeeplyNestedValueWithoutFailingConfig() {
    StringBuilder value = new StringBuilder("{\"custom.tag\":");
    for (int i = 0; i < 10_000; i++) {
      value.append('[');
    }

    Config config = configWithRuntimeTags(value.toString());

    assertEquals(Collections.emptyMap(), config.getTestOptimizationRuntimeTags());
  }

  private static void assertDetectedTags(CiVisibilityWellKnownTags tags) {
    assertEquals(SystemProperties.get("os.name"), tags.getOsPlatform().toString());
    assertEquals(SystemProperties.get("os.arch"), tags.getOsArch().toString());
    assertEquals(SystemProperties.get("os.version"), tags.getOsVersion().toString());
    assertEquals(SystemProperties.get("java.runtime.name"), tags.getRuntimeName().toString());
    assertEquals(SystemProperties.get("java.version"), tags.getRuntimeVersion().toString());
    assertEquals(SystemProperties.get("java.vendor"), tags.getRuntimeVendor().toString());
  }

  private static Config configWithRuntimeTags(String runtimeTags) {
    WithConfigExtension.injectSysConfig(TEST_OPTIMIZATION_RUNTIME_TAGS, runtimeTags);
    return Config.get();
  }
}
