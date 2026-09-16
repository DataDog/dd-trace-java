package datadog.trace.bootstrap.config.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.trace.api.ConfigCollector;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.test.util.DDJavaSpecification;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.tabletest.junit.TableTest;

@SuppressForbidden
public class StableConfigSourceTest extends DDJavaSpecification {

  @Test
  void testFileDoesntExist() {
    StableConfigSource config =
        new StableConfigSource(
            StableConfigSource.LOCAL_STABLE_CONFIG_PATH, ConfigOrigin.LOCAL_STABLE_CONFIG);

    assertEquals(0, config.getKeys().size());
    assertNull(config.getConfigId());
  }

  @Test
  void testEmptyFile() throws IOException {
    Path filePath = Files.createTempFile("testFile_", ".yaml");
    try {
      StableConfigSource config =
          new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG);
      assertEquals(0, config.getKeys().size());
      assertNull(config.getConfigId());
    } finally {
      Files.delete(filePath);
    }
  }

  @TableTest({
    "scenario       | configId | data                    ",
    "corrupt yaml   |          | not_valid_stable_config ",
    "invalid format | 12345    | this is not yaml format!"
  })
  void testFileInvalidFormat(String configId, String data) throws IOException {
    Path filePath = Files.createTempFile("testFile_", ".yaml");
    assertNotNull(filePath, "Failed to create: " + filePath);
    try {
      writeFileRaw(filePath, configId, data);
      StableConfigSource stableCfg =
          new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG);

      assertNull(stableCfg.getConfigId());
      assertEquals(0, stableCfg.getKeys().size());
    } finally {
      Files.delete(filePath);
    }
  }

  @Test
  void testNullValuesInYaml() throws IOException {
    Path filePath = Files.createTempFile("testFile_", ".yaml");
    assertNotNull(filePath, "Failed to create: " + filePath);
    try {
      // Test the scenario where YAML contains null values for apm_configuration_default and
      // apm_configuration_rules
      String yaml = "config_id: \"12345\"\napm_configuration_default:\napm_configuration_rules:\n";
      Files.write(filePath, yaml.getBytes());

      StableConfigSource stableCfg =
          new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG);

      // Should not throw NullPointerException and should handle null values gracefully
      assertEquals("12345", stableCfg.getConfigId());
      assertEquals(0, stableCfg.getKeys().size());
    } finally {
      Files.delete(filePath);
    }
  }

  @TableTest({
    "configId | defaultConfigs                    ",
    "''       | [:]                               ",
    "12345    | [DD_KEY_ONE: one, DD_KEY_TWO: two]"
  })
  void testFileValidFormat(String configId, Map<String, String> defaultConfigs) throws IOException {
    Path filePath = Files.createTempFile("testFile_", ".yaml");
    try {
      writeFileYaml(filePath, configId, defaultConfigs);
      StableConfigSource stableCfg =
          new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG);

      assertEquals(configId.isEmpty() ? null : configId, stableCfg.getConfigId());
      assertEquals(defaultConfigs.keySet(), stableCfg.getKeys());
      defaultConfigs.forEach(
          (key, value) -> assertEquals(value, stableCfg.get(key.substring("DD_".length()))));
    } finally {
      Files.delete(filePath);
    }
  }

  @ParameterizedTest
  @MethodSource("testParseInvalidLogsMappingErrorsArguments")
  void testParseInvalidLogsMappingErrors(String yaml, String expectedLogSubstring)
      throws IOException {
    Logger logbackLogger = (Logger) LoggerFactory.getLogger(StableConfigSource.class);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    logbackLogger.addAppender(listAppender);

    File tempFile = File.createTempFile("testFile_", ".yaml");
    try {
      Files.write(tempFile.toPath(), yaml.getBytes());

      StableConfigSource stableCfg =
          new StableConfigSource(tempFile.getAbsolutePath(), ConfigOrigin.LOCAL_STABLE_CONFIG);

      assertNull(stableCfg.getConfigId());
      assertEquals(0, stableCfg.getKeys().size());
      boolean hasExpectedLog =
          listAppender.list.stream()
              .anyMatch(
                  event ->
                      "WARN".equals(event.getLevel().toString())
                          && event.getFormattedMessage().contains(expectedLogSubstring));
      assertTrue(hasExpectedLog, "Expected WARN log containing: " + expectedLogSubstring);
    } finally {
      tempFile.delete();
      logbackLogger.detachAppender(listAppender);
    }
  }

  static Stream<Arguments> testParseInvalidLogsMappingErrorsArguments() {
    return Stream.of(
        arguments(
            "apm_configuration_rules:\n"
                + "      - selectors:\n"
                + "          - key: \"someKey\"\n"
                + "            matches: [\"someValue\"]\n"
                + "            operator: equals\n"
                + "        configuration:\n"
                + "          DD_SERVICE: \"test\"\n",
            "Missing 'origin' in selector"),
        arguments(
            "apm_configuration_rules:\n"
                + "      - selectors:\n"
                + "          - origin: process_arguments\n"
                + "            key: \"-Dfoo\"\n"
                + "            matches: [\"bar\"]\n"
                + "            operator: equals\n",
            "Missing 'configuration' in rule"),
        arguments(
            "apm_configuration_rules:\n"
                + "       - configuration:\n"
                + "           DD_SERVICE: \"test\"\n",
            "Missing 'selectors' in rule"),
        arguments(
            "apm_configuration_rules:\n"
                + "      - selectors: \"not-a-list\"\n"
                + "        configuration:\n"
                + "          DD_SERVICE: \"test\"\n",
            "'selectors' must be a list, but got: String"),
        arguments(
            "apm_configuration_rules:\n"
                + "       - selectors:\n"
                + "           - \"not-a-map\"\n"
                + "         configuration:\n"
                + "           DD_SERVICE: \"test\"\n",
            "Each selector must be a map, but got: String"),
        arguments(
            "apm_configuration_rules:\n"
                + "      - selectors:\n"
                + "          - origin: process_arguments\n"
                + "            key: \"-Dfoo\"\n"
                + "            matches: [\"bar\"]\n"
                + "            operator: equals\n"
                + "        configuration: \"not-a-map\"\n",
            "'configuration' must be a map, but got: String"),
        arguments(
            "apm_configuration_rules:\n"
                + "      - selectors:\n"
                + "          - origin: process_arguments\n"
                + "            key: \"-Dfoo\"\n"
                + "            matches: [\"bar\"]\n"
                + "            operator: equals\n"
                + "        configuration: 12345\n",
            "'configuration' must be a map, but got: Integer"),
        arguments(
            "apm_configuration_rules:\n" + "      - \"not-a-map\"\n",
            "Rule must be a map, but got: String"),
        arguments(
            "apm_configuration_rules:\n"
                + "     - selectors:\n"
                + "         - origin: process_arguments\n"
                + "           key: \"-Dfoo\"\n"
                + "           matches: \"not-a-list\"\n"
                + "           operator: equals\n"
                + "       configuration:\n"
                + "         DD_SERVICE: \"test\"\n",
            "'matches' must be a list, but got: String"),
        arguments(
            "apm_configuration_rules:\n"
                + "     - selectors:\n"
                + "         - origin: process_arguments\n"
                + "           key: \"-Dfoo\"\n"
                + "           matches: [\"bar\"]\n"
                + "       configuration:\n"
                + "         DD_SERVICE: \"test\"\n",
            "Missing 'operator' in selector"),
        arguments(
            "apm_configuration_rules:\n"
                + "     - selectors:\n"
                + "         - origin: process_arguments\n"
                + "           key: \"-Dfoo\"\n"
                + "           matches: [\"bar\"]\n"
                + "           operator: 12345\n"
                + "       configuration:\n"
                + "         DD_SERVICE: \"test\"\n",
            "'operator' must be a string, but got: Integer"),
        arguments(
            "apm_configuration_rules:\n"
                + "      - selectors:\n"
                + "          # origin is missing entirely, should trigger NullPointerException\n"
                + "          - key: \"-Dfoo\"\n"
                + "            matches: [\"bar\"]\n"
                + "            operator: equals\n",
            "YAML mapping error in stable configuration file"));
  }

  @Test
  @SuppressForbidden
  void testConfigIdExistsInConfigCollectorWhenUsingStableConfigSource() throws Exception {
    Path filePath = Files.createTempFile("testFile_", ".yaml");
    String expectedConfigId = "123";

    // Create YAML content with config_id and some configuration
    String yamlContent =
        "config_id: "
            + expectedConfigId
            + "\napm_configuration_default:\n  DD_SERVICE: test-service\n  DD_ENV: test-env\n";
    Files.write(filePath, yamlContent.getBytes());

    // Clear any existing collected config
    ConfigCollector.get().collect();

    try {
      StableConfigSource stableConfigSource =
          new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG);

      // Create ConfigProvider via reflection (constructor is private)
      Constructor<ConfigProvider> constructor =
          ConfigProvider.class.getDeclaredConstructor(ConfigProvider.Source[].class);
      constructor.setAccessible(true);
      ConfigProvider configProvider =
          constructor.newInstance((Object) new ConfigProvider.Source[] {stableConfigSource});

      // Trigger config collection by getting a value
      configProvider.getString("SERVICE", "default-service");

      Map<ConfigOrigin, Map<String, ConfigSetting>> collectedConfigs =
          ConfigCollector.get().collect();
      Map<String, ConfigSetting> localStableConfigs =
          collectedConfigs.get(ConfigOrigin.LOCAL_STABLE_CONFIG);
      assertNotNull(localStableConfigs, "No configs collected for LOCAL_STABLE_CONFIG origin");
      ConfigSetting serviceSetting = localStableConfigs.get("SERVICE");
      assertNotNull(serviceSetting, "No SERVICE setting collected");
      assertEquals(expectedConfigId, serviceSetting.configId);
      assertEquals("test-service", serviceSetting.value);
      assertEquals(ConfigOrigin.LOCAL_STABLE_CONFIG, serviceSetting.origin);
    } finally {
      Files.delete(filePath);
    }
  }

  @Test
  void testStableConfigGetHandlesPresentAndMissingKeys() {
    Map<String, Object> configMap = new HashMap<>();
    configMap.put("DD_SERVICE", "test-service");
    configMap.put("DD_PORT", 8126);

    StableConfigSource.StableConfig config =
        new StableConfigSource.StableConfig("config-123", configMap);

    // Present String value: hits the non-null branch of the ternary in get()
    assertEquals("test-service", config.get("DD_SERVICE"));
    // Present non-String value: exercises String.valueOf on a non-null, non-String object
    assertEquals("8126", config.get("DD_PORT"));
    // Missing key: hits the null branch of the ternary in get()
    assertNull(config.get("DD_MISSING"));

    assertEquals("config-123", config.getConfigId());
    assertEquals(configMap.keySet(), config.getKeys());
  }

  @Test
  void testStableConfigEmpty() {
    StableConfigSource.StableConfig empty = StableConfigSource.StableConfig.EMPTY;
    assertNull(empty.get("DD_SERVICE"));
    assertNull(empty.getConfigId());
    assertEquals(0, empty.getKeys().size());
  }

  private static void writeFileYaml(
      Path filePath, String configId, Map<String, String> defaultConfigs) throws IOException {
    Map<String, Object> yamlData = new HashMap<>();

    if (configId != null && !configId.isEmpty()) {
      yamlData.put("config_id", configId);
    }

    if (defaultConfigs != null && !defaultConfigs.isEmpty()) {
      yamlData.put("apm_configuration_default", defaultConfigs);
    }

    DumpSettings settings = DumpSettings.builder().build();
    Dump dump = new Dump(settings);
    String yamlContent = dump.dumpToString(yamlData);

    try (FileWriter writer = new FileWriter(filePath.toFile())) {
      writer.write(yamlContent);
    }
  }

  private static void writeFileRaw(Path filePath, String configId, String data) throws IOException {
    String content = configId + "\n" + data;
    Files.write(filePath, content.getBytes(), StandardOpenOption.WRITE);
  }
}
