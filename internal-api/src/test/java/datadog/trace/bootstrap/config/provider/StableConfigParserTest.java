package datadog.trace.bootstrap.config.provider;

import static datadog.trace.bootstrap.config.provider.StableConfigParser.MAX_FILE_SIZE_BYTES;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectEnvConfig;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import datadog.trace.bootstrap.config.provider.StableConfigSource.StableConfig;
import datadog.trace.config.inversion.ConfigHelper;
import datadog.trace.config.inversion.ConfigHelper.StrictnessPolicy;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.tabletest.junit.TableTest;

@ExtendWith(WithConfigExtension.class)
class StableConfigParserTest {

  @TempDir Path tempDir;

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
  @WithConfig(key = "DD_SERVICE", value = "mysvc", env = true, addPrefix = false)
  void testParseValid() throws IOException {
    // From the below yaml, only apm_configuration_default and the second selector should be
    // applied: We use the first matching rule and discard the rest
    String yaml =
        "\n"
            + "config_id: 12345\n"
            + "apm_configuration_default:\n"
            + "    KEY_ONE: \"default\"\n"
            + "    KEY_TWO: true\n"
            + "apm_configuration_rules:\n"
            + "  - selectors:\n"
            + "    - origin: language\n"
            + "      matches: [\"golang\"]\n"
            + "      operator: equals\n"
            + "    configuration:\n"
            + "      KEY_ONE: \"ignored\"\n"
            + "  - selectors:\n"
            + "    - origin: language\n"
            + "      matches: [\"Java\"]\n"
            + "      operator: equals\n"
            + "    configuration:\n"
            + "      KEY_ONE: \"rules\"\n"
            + "      KEY_THREE: 1\n"
            + "  - selectors:\n"
            + "    - origin: environment_variables\n"
            + "      key: \"DD_SERVICE\"\n"
            + "      operator: equals\n"
            + "      matches: [\"mysvc\"]\n"
            + "    configuration:\n"
            + "      KEY_FOUR: \"ignored\"\n"
            + "  - selectors:\n"
            + "    - origin: process_arguments\n"
            + "      key: \"-Darg1\"\n"
            + "      operator: exists\n"
            + "    configuration:\n"
            + "      KEY_FIVE: \"ignored\"\n";

    StableConfig config = StableConfigParser.parse(writeYaml(yaml));

    assertEquals(3, config.getKeys().size());
    assertEquals("12345", config.getConfigId().trim());
    assertEquals("rules", config.get("KEY_ONE"));
    assertEquals("true", config.get("KEY_TWO"));
    assertEquals("1", config.get("KEY_THREE"));
  }

  @Test
  @WithConfig(key = "test_parse_and_template", value = "myservice", addPrefix = false)
  void testParseAndTemplate() throws IOException {
    String yaml =
        "\n"
            + "    apm_configuration_rules:\n"
            + "      - selectors:\n"
            + "        - origin: process_arguments\n"
            + "          key: \"-Dtest_parse_and_template\"\n"
            + "          operator: exists\n"
            + "        configuration:\n"
            + "          DD_SERVICE: {{process_arguments['-Dtest_parse_and_template']}}\n";

    StableConfig config = StableConfigParser.parse(writeYaml(yaml));

    assertEquals("myservice", config.get("DD_SERVICE"));
  }

  @TableTest({
    "scenario                     | origin                | matches           | operator             | key                  | expectMatch",
    "language equals              | language              | [java]            | equals               | ''                   | true       ",
    "language case insensitivity  | LANGUAGE              | [JaVa]            | EQUALS               | ''                   | true       ",
    "language equals one of many  | language              | [java, golang]    | equals               | ''                   | true       ",
    "language starts_with         | language              | [java]            | starts_with          | ''                   | true       ",
    "language not equals          | language              | [golang]          | equals               | ''                   | false      ",
    "language exists              | language              | [java]            | exists               | ''                   | false      ",
    "language unexpected operator | language              | [java]            | something unexpected | ''                   | false      ",
    "env exists empty matches     | environment_variables | []                | exists               | DD_TAGS              | true       ",
    "env exists null matches      | environment_variables |                   | exists               | DD_TAGS              | true       ",
    "env contains                 | environment_variables | ['team:apm']      | contains             | DD_TAGS              | true       ",
    "env case insensitivity       | ENVIRONMENT_VARIABLES | ['TeAm:ApM']      | CoNtAiNs             | Dd_TaGs              | true       ",
    "env not equals               | environment_variables | ['team:apm']      | equals               | DD_TAGS              | false      ",
    "env starts_with              | environment_variables | ['team:apm']      | starts_with          | DD_TAGS              | true       ",
    "env equals boolean           | environment_variables | [true]            | equals               | DD_PROFILING_ENABLED | true       ",
    "env not equals boolean       | environment_variables | [abcdefg]         | equals               | DD_PROFILING_ENABLED | false      ",
    "env equals boolean again     | environment_variables | [true]            | equals               | DD_PROFILING_ENABLED | true       ",
    "env equals one of many       | environment_variables | [mysvc, othersvc] | equals               | DD_SERVICE           | true       ",
    "env starts_with service      | environment_variables | [my]              | starts_with          | DD_SERVICE           | true       ",
    "env ends_with service        | environment_variables | [svc]             | ends_with            | DD_SERVICE           | true       ",
    "env contains service         | environment_variables | [svc]             | contains             | DD_SERVICE           | true       ",
    "env not contains service     | environment_variables | [other]           | contains             | DD_SERVICE           | false      ",
    "env equals null key          | environment_variables | []                | equals               |                      | false      ",
    "env equals null matches      | environment_variables |                   | equals               | DD_SERVICE           | false      ",
    "language null operator       | language              | [java]            |                      | ''                   | false      ",
    "process argument exists      | process_arguments     |                   | exists               | -Dtest_selectorMatch | true       ",
    "process argument not exists  | process_arguments     |                   | exists               | -Darg2               | false      ",
    "process argument equals      | process_arguments     | [value1]          | equals               | -Dtest_selectorMatch | true       ",
    "process argument not equals  | process_arguments     | [value2]          | equals               | -Dtest_selectorMatch | false      "
  })
  @MethodSource("testSelectorMatchArguments")
  @WithConfig(key = "DD_PROFILING_ENABLED", value = "true", env = true, addPrefix = false)
  @WithConfig(key = "DD_SERVICE", value = "mysvc", env = true, addPrefix = false)
  @WithConfig(key = "DD_TAGS", value = "team:apm,component:web", env = true, addPrefix = false)
  @WithConfig(key = "test_selectorMatch", value = "value1", addPrefix = false)
  void testSelectorMatch(
      String origin, List<String> matches, String operator, String key, boolean expectMatch) {
    boolean match = StableConfigParser.selectorMatch(origin, matches, operator, key);

    assertEquals(expectMatch, match);
  }

  static Stream<Arguments> testSelectorMatchArguments() {
    return Stream.of(
        // env contains null match
        arguments("environment_variables", singletonList(null), "contains", "DD_SERVICE", false));
  }

  @Test
  void testDuplicateEntriesNotAllowed() throws IOException {
    String yaml = "\n  config_id: 12345\n  config_id: 67890\n  ";
    String filePath = writeYaml(yaml);

    RuntimeException exception =
        assertThrows(RuntimeException.class, () -> StableConfigParser.parse(filePath));

    assertTrue(
        exception.getMessage().contains("found duplicate key config_id"), exception.getMessage());
  }

  @Test
  void testConfigIdOnly() throws IOException {
    String yaml = "\n  config_id: 12345\n  ";

    StableConfig config = StableConfigParser.parse(writeYaml(yaml));

    assertNotNull(config);
    assertEquals("12345", config.getConfigId());
    assertEquals(0, config.getKeys().size());
  }

  @Test
  void testParseInvalid() throws IOException {
    // If any piece of the file is invalid, the whole file is rendered invalid and an exception is
    // thrown
    String yaml =
        "\n"
            + "  something-irrelevant: \"\"\n"
            + "  config_id: 12345\n"
            + "  something : not : expected << and weird format\n"
            + "      inufjka <<\n"
            + "      [a,\n"
            + "          b,\n"
            + "              c,\n"
            + "                  d]\n"
            + "  apm_configuration_default:\n"
            + "    KEY_ONE: value_one\n"
            + "    KEY_TWO: \"value_two\"\n"
            + "    KEY_THREE: 100\n"
            + "    KEY_FOUR: true\n"
            + "    KEY_FIVE: [a,b,c,d]\n"
            + "  something-else-irrelevant: value-irrelevant\n"
            + "  ";
    String filePath = writeYaml(yaml);

    assertThrows(Exception.class, () -> StableConfigParser.parse(filePath));
  }

  @Test
  void testFileOverMaxSize() throws IOException {
    // Create a file with valid contents, but bigger than MAX_FILE_SIZE_BYTES
    String baseYaml =
        "\n"
            + "config_id: 12345\n"
            + "apm_configuration_default:\n"
            + "    KEY_ONE: \"value_one\"\n"
            + "apm_configuration_rules:\n";
    String builderYaml =
        "\n"
            + "  - selectors:\n"
            + "    - origin: language\n"
            + "      matches: [\"Java\"]\n"
            + "      operator: equals\n"
            + "    configuration:\n"
            + "      KEY_TWO: \"value_two\"\n";
    StringBuilder bigYaml = new StringBuilder(baseYaml);
    while (bigYaml.length() < MAX_FILE_SIZE_BYTES) {
      bigYaml.append(builderYaml);
    }

    StableConfig config = StableConfigParser.parse(writeYaml(bigYaml.toString()));

    assertSame(StableConfig.EMPTY, config);
  }

  @TableTest({
    "scenario           | templateVar                                                                                                   | envKey | envVal | expect             ",
    "env var            | \"{{environment_variables['DD_KEY']}}\"                                                                       | DD_KEY | value  | value              ",
    "missing env var    | \"{{environment_variables['DD_KEY']}}\"                                                                       |        |        | ''                 ",
    "empty template     | '{{}}'                                                                                                        |        |        | ''                 ",
    "single braces      | '{}'                                                                                                          |        |        | '{}'               ",
    "lowercase env var  | \"{{environment_variables['dd_key']}}\"                                                                       | DD_KEY | value  | value              ",
    "unterminated quote | \"{{environment_variables['DD_KEY}}\"                                                                         | DD_KEY | value  | ''                 ",
    "header and footer  | \"header-{{environment_variables['DD_KEY']}}-footer\"                                                         | DD_KEY | value  | header-value-footer",
    "multiple templates | \"{{environment_variables['HEADER']}}{{environment_variables['DD_KEY']}}{{environment_variables['FOOTER']}}\" | DD_KEY | value  | value              "
  })
  void testProcessTemplateValidCases(
      String templateVar, String envKey, String envVal, String expect) throws IOException {
    if (envKey != null) {
      injectEnvConfig(envKey, envVal);
    }

    assertEquals(expect, StableConfigParser.processTemplate(templateVar));
  }

  @TableTest({
    "scenario            | templateVar                            | expect                                     ",
    "empty variable name | \"{{environment_variables['']}}\"      | Empty environment variable name in template",
    "unterminated        | \"{{environment_variables['DD_KEY']}\" | Unterminated template in config            "
  })
  void testProcessTemplateErrorCases(String templateVar, String expect) {
    IOException exception =
        assertThrows(IOException.class, () -> StableConfigParser.processTemplate(templateVar));

    assertEquals(expect, exception.getMessage());
  }

  @Test
  void testNullAndEmptyValuesInYaml() throws IOException {
    String yaml =
        "\n"
            + "config_id: \"12345\"\n"
            + "apm_configuration_default:\n"
            + "apm_configuration_rules:\n";

    StableConfig config = StableConfigParser.parse(writeYaml(yaml));

    assertEquals("12345", config.getConfigId());
    assertTrue(config.getKeys().isEmpty());
  }

  @Test
  void testCompletelyEmptyValuesInYaml() throws IOException {
    String yaml =
        "\n"
            + "config_id: \"12345\"\n"
            + "apm_configuration_default: \n"
            + "apm_configuration_rules: \n";

    StableConfig config = StableConfigParser.parse(writeYaml(yaml));

    assertEquals("12345", config.getConfigId());
    assertTrue(config.getKeys().isEmpty());
  }

  private String writeYaml(String yaml) throws IOException {
    Path filePath = Files.createTempFile(tempDir, "testFile_", ".yaml");
    Files.write(filePath, yaml.getBytes(UTF_8));
    return filePath.toString();
  }
}
