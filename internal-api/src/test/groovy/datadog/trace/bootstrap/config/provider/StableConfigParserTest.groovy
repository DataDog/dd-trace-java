package datadog.trace.bootstrap.config.provider
import datadog.trace.test.util.DDSpecification
import java.nio.file.Files
import java.nio.file.Path

class StableConfigParserTest extends DDSpecification {
  def "test parse valid"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }
    injectEnvConfig("DD_SERVICE", "mysvc")
    // From the below yaml, only apm_configuration_default and the second selector should be applied: We use the first matching rule and discard the rest
    String yaml = """
config_id: 12345
apm_configuration_default:
    KEY_ONE: "default"
    KEY_TWO: true
apm_configuration_rules:
  - selectors:
    - origin: language
      matches: ["golang"]
      operator: equals
    configuration:
      KEY_ONE: "ignored"
  - selectors:
    - origin: language
      matches: ["Java"]
      operator: equals
    configuration:
      KEY_ONE: "rules"
      KEY_THREE: 1
  - selectors:
    - origin: environment_variables
      key: "DD_SERVICE"
      operator: equals
      matches: ["mysvc"]
    configuration:
      KEY_FOUR: "ignored"
"""
    try {
      Files.write(filePath, yaml.getBytes())
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource.StableConfig cfg
    try {
      cfg = StableConfigParser.parse(filePath.toString())
    } catch (Exception e) {
      throw new AssertionError("Failed to parse the file: ${e.message}")
    }

    then:
    def keys = cfg.getKeys()
    keys.size() == 3
    cfg.getConfigId().trim() == ("12345")
    cfg.get("KEY_ONE") == "rules"
    cfg.get("KEY_TWO") == "true"
    cfg.get("KEY_THREE") == "1"
    Files.delete(filePath)
  }

  def "test selectorMatch"() {
    when:
    // Env vars
    injectEnvConfig("DD_PROFILING_ENABLED", "true")
    injectEnvConfig("DD_SERVICE", "mysvc")
    injectEnvConfig("DD_TAGS", "team:apm,component:web")
    def match = StableConfigParser.selectorMatch(origin, matches, operator, key)

    then:
    match == expectMatch

    where:
    origin | matches | operator | key | expectMatch
    "language" | ["java"] | "equals" | "" | true
    "LANGUAGE" | ["JaVa"] | "EQUALS" | "" | true // check case insensitivity
    "language" | ["java", "golang"] | "equals" | "" | true
    "language" | ["java"] | "starts_with" | "" | true
    "language" | ["golang"] | "equals" | "" | false
    "language" | ["java"] | "exists" | "" | false
    "language" | ["java"] | "something unexpected" | "" | false
    "environment_variables" | [] | "exists" | "DD_TAGS" | true
    "environment_variables" | ["team:apm"] | "contains" | "DD_TAGS" | true
    "ENVIRONMENT_VARIABLES" | ["TeAm:ApM"] | "CoNtAiNs" | "Dd_TaGs" | true // check case insensitivity
    "environment_variables" | ["team:apm"] | "equals" | "DD_TAGS" | false
    "environment_variables" | ["team:apm"] | "starts_with" | "DD_TAGS" | true
    "environment_variables" | ["true"] | "equals" | "DD_PROFILING_ENABLED" | true
    "environment_variables" | ["abcdefg"] | "equals" | "DD_PROFILING_ENABLED" | false
    "environment_variables" | ["true"] | "equals" | "DD_PROFILING_ENABLED" | true
    "environment_variables" | ["mysvc", "othersvc"] | "equals" | "DD_SERVICE" | true
    "environment_variables" | ["my"] | "starts_with" | "DD_SERVICE" | true
    "environment_variables" | ["svc"] | "ends_with" | "DD_SERVICE" | true
    "environment_variables" | ["svc"] | "contains" | "DD_SERVICE" | true
    "environment_variables" | ["other"] | "contains" | "DD_SERVICE" | false
    "environment_variables" | [null] | "contains" | "DD_SERVICE" | false
  }

  def "test duplicate entries"() {
    // When duplicate keys are encountered, snakeyaml preserves the last value by default
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }
    String yaml = """
  config_id: 12345
  config_id: 67890
  apm_configuration_default:
    DD_KEY: value_1
  apm_configuration_default:
    DD_KEY: value_2
  """

    try {
      Files.write(filePath, yaml.getBytes())
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource.StableConfig cfg
    try {
      cfg = StableConfigParser.parse(filePath.toString())
    } catch (Exception e) {
      throw new AssertionError("Failed to parse the file: ${e.message}")
    }

    then:
    cfg != null
    cfg.getConfigId() == "67890"
    cfg.get("DD_KEY") == "value_2"
  }

  def "test config_id only"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }
    String yaml = """
  config_id: 12345
  """
    try {
      Files.write(filePath, yaml.getBytes())
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource.StableConfig cfg
    try {
      cfg = StableConfigParser.parse(filePath.toString())
    } catch (Exception e) {
      throw new AssertionError("Failed to parse the file: ${e.message}")
    }

    then:
    cfg != null
    cfg.getConfigId() == "12345"
    cfg.getKeys().size() == 0
  }

  def "test parse invalid"() {
    // If any piece of the file is invalid, the whole file is rendered invalid and an exception is thrown
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }
    String yaml = """
  something-irrelevant: ""
  config_id: 12345
  something : not : expected << and weird format
      inufjka <<
      [a,
          b,
              c,
                  d]
  apm_configuration_default:
    KEY_ONE: value_one
    KEY_TWO: "value_two"
    KEY_THREE: 100
    KEY_FOUR: true
    KEY_FIVE: [a,b,c,d]
  something-else-irrelevant: value-irrelevant
  """
    try {
      Files.write(filePath, yaml.getBytes())
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource.StableConfig cfg
    Exception exception = null
    try {
      cfg = StableConfigParser.parse(filePath.toString())
    } catch (Exception e) {
      exception = e
    }

    then:
    exception != null
    cfg == null
    Files.delete(filePath)
  }

  def "test processTemplate valid cases"() {
    when:
    if (envKey != null) {
      injectEnvConfig(envKey, envVal)
    }

    then:
    StableConfigParser.processTemplate(templateVar) == expect

    where:
    templateVar | envKey | envVal | expect
    "{{environment_variables['DD_KEY']}}" | "DD_KEY" | "value" | "value"
    "{{environment_variables['DD_KEY']}}" | null | null | "UNDEFINED"
    "{{}}" | null | null | "UNDEFINED"
    "{}" | null | null | "{}"
    "{{environment_variables['dd_key']}}" | "DD_KEY" | "value" | "value"
    "{{environment_variables['DD_KEY}}" | "DD_KEY" | "value" | "UNDEFINED"
    "header-{{environment_variables['DD_KEY']}}-footer" | "DD_KEY" | "value" | "header-value-footer"
    "{{environment_variables['HEADER']}}{{environment_variables['DD_KEY']}}{{environment_variables['FOOTER']}}" | "DD_KEY" | "value" | "UNDEFINEDvalueUNDEFINED"
  }

  def "test processTemplate error cases"() {
    when:
    StableConfigParser.processTemplate(templateVar)

    then:
    def e = thrown(IOException)
    e.message == expect

    where:
    templateVar | expect
    "{{environment_variables['']}}" | "Empty environment variable name in template"
    "{{environment_variables['DD_KEY']}" | "Unterminated template in config"
  }
}
