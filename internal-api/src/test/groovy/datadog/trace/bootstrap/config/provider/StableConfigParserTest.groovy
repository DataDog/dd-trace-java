package datadog.trace.bootstrap.config.provider
import datadog.trace.test.util.DDSpecification
import java.nio.file.Files
import java.nio.file.Path
import datadog.trace.config.inversion.ConfigHelper

class StableConfigParserTest extends DDSpecification {

  def strictness

  def setup(){
    strictness = ConfigHelper.get().configInversionStrictFlag()
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
  }

  def cleanup(){
    ConfigHelper.get().setConfigInversionStrict(strictness)
  }
  def "test parse valid"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
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
  - selectors:
    - origin: process_arguments
      key: "-Darg1"
      operator: exists
    configuration:
      KEY_FIVE: "ignored"
"""
    Files.write(filePath, yaml.getBytes())
    StableConfigSource.StableConfig cfg = StableConfigParser.parse(filePath.toString())

    then:
    def keys = cfg.getKeys()
    keys.size() == 3
    cfg.getConfigId().trim() == ("12345")
    cfg.get("KEY_ONE") == "rules"
    cfg.get("KEY_TWO") == "true"
    cfg.get("KEY_THREE") == "1"
    Files.delete(filePath)
  }

  def "test parse and template"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
    String yaml = """
    apm_configuration_rules:
      - selectors:
        - origin: process_arguments
          key: "-Dtest_parse_and_template"
          operator: exists
        configuration:
          DD_SERVICE: {{process_arguments['-Dtest_parse_and_template']}}
"""
    System.setProperty("test_parse_and_template", "myservice")
    Files.write(filePath, yaml.getBytes())
    StableConfigSource.StableConfig cfg = StableConfigParser.parse(filePath.toString())

    then:
    cfg.get("DD_SERVICE") == "myservice"
  }

  def "test selectorMatch"() {
    when:
    // Env vars
    injectEnvConfig("DD_PROFILING_ENABLED", "true")
    injectEnvConfig("DD_SERVICE", "mysvc")
    injectEnvConfig("DD_TAGS", "team:apm,component:web")
    System.setProperty("test_selectorMatch", "value1")

    def match = StableConfigParser.selectorMatch(origin, matches, operator, key)

    then:
    match == expectMatch

    where:
    origin                  | matches               | operator               | key                    | expectMatch
    "language"              | ["java"]              | "equals"               | ""                     | true
    "LANGUAGE"              | ["JaVa"]              | "EQUALS"               | ""                     | true // check case insensitivity
    "language"              | ["java", "golang"]    | "equals"               | ""                     | true
    "language"              | ["java"]              | "starts_with"          | ""                     | true
    "language"              | ["golang"]            | "equals"               | ""                     | false
    "language"              | ["java"]              | "exists"               | ""                     | false
    "language"              | ["java"]              | "something unexpected" | ""                     | false
    "environment_variables" | []                    | "exists"               | "DD_TAGS"              | true
    "environment_variables" | null                  | "exists"               | "DD_TAGS"              | true
    "environment_variables" | ["team:apm"]          | "contains"             | "DD_TAGS"              | true
    "ENVIRONMENT_VARIABLES" | ["TeAm:ApM"]          | "CoNtAiNs"             | "Dd_TaGs"              | true // check case insensitivity
    "environment_variables" | ["team:apm"]          | "equals"               | "DD_TAGS"              | false
    "environment_variables" | ["team:apm"]          | "starts_with"          | "DD_TAGS"              | true
    "environment_variables" | ["true"]              | "equals"               | "DD_PROFILING_ENABLED" | true
    "environment_variables" | ["abcdefg"]           | "equals"               | "DD_PROFILING_ENABLED" | false
    "environment_variables" | ["true"]              | "equals"               | "DD_PROFILING_ENABLED" | true
    "environment_variables" | ["mysvc", "othersvc"] | "equals"               | "DD_SERVICE"           | true
    "environment_variables" | ["my"]                | "starts_with"          | "DD_SERVICE"           | true
    "environment_variables" | ["svc"]               | "ends_with"            | "DD_SERVICE"           | true
    "environment_variables" | ["svc"]               | "contains"             | "DD_SERVICE"           | true
    "environment_variables" | ["other"]             | "contains"             | "DD_SERVICE"           | false
    "environment_variables" | [null]                | "contains"             | "DD_SERVICE"           | false
    "environment_variables" | []                    | "equals"               | null                   | false
    "environment_variables" | null                  | "equals"               | "DD_SERVICE"           | false
    "language"              | ["java"]              | null                   | ""                     | false
    "process_arguments"     | null                  | "exists"               | "-Dtest_selectorMatch" | true
    "process_arguments"     | null                  | "exists"               | "-Darg2"               | false
    "process_arguments"     | ["value1"]            | "equals"               | "-Dtest_selectorMatch" | true
    "process_arguments"     | ["value2"]            | "equals"               | "-Dtest_selectorMatch" | false
  }

  def "test duplicate entries not allowed"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
    String yaml = """
  config_id: 12345
  config_id: 67890
  """
    Files.write(filePath, yaml.getBytes())
    StableConfigParser.parse(filePath.toString())

    then:
    def ex = thrown(RuntimeException)

    and:
    ex.message.contains "found duplicate key config_id"
  }

  def "test config_id only"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
    String yaml = """
  config_id: 12345
  """
    Files.write(filePath, yaml.getBytes())
    StableConfigSource.StableConfig cfg = StableConfigParser.parse(filePath.toString())

    then:
    cfg != null
    cfg.getConfigId() == "12345"
    cfg.getKeys().size() == 0
  }

  def "test parse invalid"() {
    // If any piece of the file is invalid, the whole file is rendered invalid and an exception is thrown
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
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
    Files.write(filePath, yaml.getBytes())
    StableConfigSource.StableConfig cfg = null
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

  def "test file over max size"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }

    // Create a file with valid contents, but bigger than MAX_FILE_SIZE_BYTES
    String baseYaml = """
config_id: 12345
apm_configuration_default:
    KEY_ONE: "value_one"
apm_configuration_rules:
"""
    String builderYaml = """
  - selectors:
    - origin: language
      matches: ["Java"]
      operator: equals
    configuration:
      KEY_TWO: "value_two"
"""
    String bigYaml = baseYaml
    while(bigYaml.size() < StableConfigParser.MAX_FILE_SIZE_BYTES) {
      bigYaml += builderYaml
    }

    try {
      Files.write(filePath, bigYaml.getBytes())
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
    cfg == StableConfigSource.StableConfig.EMPTY
  }

  def "test processTemplate valid cases"() {
    when:
    if (envKey != null) {
      injectEnvConfig(envKey, envVal)
    }

    then:
    StableConfigParser.processTemplate(templateVar) == expect

    where:
    templateVar                                                                                                 | envKey   | envVal  | expect
    "{{environment_variables['DD_KEY']}}"                                                                       | "DD_KEY" | "value" | "value"
    "{{environment_variables['DD_KEY']}}"                                                                       | null     | null    | ""
    "{{}}"                                                                                                      | null     | null    | ""
    "{}"                                                                                                        | null     | null    | "{}"
    "{{environment_variables['dd_key']}}"                                                                       | "DD_KEY" | "value" | "value"
    "{{environment_variables['DD_KEY}}"                                                                         | "DD_KEY" | "value" | ""
    "header-{{environment_variables['DD_KEY']}}-footer"                                                         | "DD_KEY" | "value" | "header-value-footer"
    "{{environment_variables['HEADER']}}{{environment_variables['DD_KEY']}}{{environment_variables['FOOTER']}}" | "DD_KEY" | "value" | "value"
  }

  def "test processTemplate error cases"() {
    when:
    StableConfigParser.processTemplate(templateVar)

    then:
    def e = thrown(IOException)
    e.message == expect

    where:
    templateVar                          | expect
    "{{environment_variables['']}}"      | "Empty environment variable name in template"
    "{{environment_variables['DD_KEY']}" | "Unterminated template in config"
  }

  def "test null and empty values in YAML"() {
    given:
    Path filePath = Files.createTempFile("testFile_", ".yaml")

    when:
    String yaml = """
config_id: "12345"
apm_configuration_default:
apm_configuration_rules:
"""
    Files.write(filePath, yaml.getBytes())
    StableConfigSource.StableConfig cfg = StableConfigParser.parse(filePath.toString())

    then:
    cfg.getConfigId() == "12345"
    cfg.getKeys().isEmpty()

    cleanup:
    Files.delete(filePath)
  }

  def "test completely empty values in YAML"() {
    given:
    Path filePath = Files.createTempFile("testFile_", ".yaml")

    when:
    String yaml = """
config_id: "12345"
apm_configuration_default: 
apm_configuration_rules: 
"""
    Files.write(filePath, yaml.getBytes())
    StableConfigSource.StableConfig cfg = StableConfigParser.parse(filePath.toString())

    then:
    cfg.getConfigId() == "12345"
    cfg.getKeys().isEmpty()

    cleanup:
    Files.delete(filePath)
  }
}
