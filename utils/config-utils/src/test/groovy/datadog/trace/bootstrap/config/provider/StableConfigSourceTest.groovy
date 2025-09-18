package datadog.trace.bootstrap.config.provider

import datadog.trace.api.ConfigCollector

import static java.util.Collections.singletonMap

import datadog.trace.api.ConfigOrigin
import datadog.trace.bootstrap.config.provider.stableconfig.Rule
import datadog.trace.bootstrap.config.provider.stableconfig.Selector
import datadog.trace.bootstrap.config.provider.stableconfig.StableConfig
import datadog.trace.test.util.DDSpecification
import org.snakeyaml.engine.v2.api.Dump
import org.snakeyaml.engine.v2.api.DumpSettings
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class StableConfigSourceTest extends DDSpecification {

  def "test file doesn't exist"() {
    setup:
    StableConfigSource config = new StableConfigSource(StableConfigSource.LOCAL_STABLE_CONFIG_PATH, ConfigOrigin.LOCAL_STABLE_CONFIG)

    expect:
    config.getKeys().size() == 0
    config.getConfigId() == null
  }

  def "test empty file"() {
    given:
    Path filePath = Files.createTempFile("testFile_", ".yaml")

    when:
    StableConfigSource config = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)
    then:
    config.getKeys().size() == 0
    config.getConfigId() == null

    cleanup:
    Files.delete(filePath)
  }

  def "test file invalid format"() {
    // StableConfigSource must handle the exception thrown by StableConfigParser.parse(filePath) gracefully
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
    writeFileRaw(filePath, configId, data)
    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    stableCfg.getConfigId() == null
    stableCfg.getKeys().size() == 0

    cleanup:
    Files.delete(filePath)

    where:
    configId | data
    null     | corruptYaml
    "12345"  | "this is not yaml format!"
  }

  def "test null values in YAML"() {
    when:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    then:
    if (filePath == null) {
      throw new AssertionError("Failed to create: " + filePath)
    }

    when:
    // Test the scenario where YAML contains null values for apm_configuration_default and apm_configuration_rules
    String yaml = """
config_id: "12345"
apm_configuration_default:
apm_configuration_rules:
"""
    Files.write(filePath, yaml.getBytes())
    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    // Should not throw NullPointerException and should handle null values gracefully
    stableCfg.getConfigId() == "12345"
    stableCfg.getKeys().size() == 0
    Files.delete(filePath)
  }

  def "test file valid format"() {
    given:
    Path filePath = Files.createTempFile("testFile_", ".yaml")

    when:
    StableConfig stableConfigYaml = new StableConfig(configId, defaultConfigs)
    writeFileYaml(filePath, stableConfigYaml)
    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    for (key in defaultConfigs.keySet()) {
      String keyString = (String) key
      keyString = keyString.substring(4) // Cut `DD_`
      stableCfg.get(keyString) == defaultConfigs.get(key)
    }
    // All configs from MatchingRule should be applied
    if (ruleConfigs.contains(sampleMatchingRule)) {
      for (key in sampleMatchingRule.getConfiguration().keySet()) {
        String keyString = (String) key
        keyString = keyString.substring(4) // Cut `DD_`
        stableCfg.get(keyString) == defaultConfigs.get(key)
      }
    }
    // None of the configs from NonMatchingRule should be applied
    if (ruleConfigs.contains(sampleNonMatchingRule)) {
      Set<String> cfgKeys = stableCfg.getKeys()
      for (key in sampleMatchingRule.getConfiguration().keySet()) {
        String keyString = (String) key
        keyString = keyString.substring(4) // Cut `DD_`
        !cfgKeys.contains(keyString)
      }
    }

    cleanup:
    Files.delete(filePath)

    where:
    configId | defaultConfigs                             | ruleConfigs
    ""       | [:]                                        | Arrays.asList(new Rule())
    "12345"  | ["DD_KEY_ONE": "one", "DD_KEY_TWO": "two"] | Arrays.asList(sampleMatchingRule, sampleNonMatchingRule)
  }

  def "test parse invalid logs mapping errors"() {
    given:
    Logger logbackLogger = (Logger) LoggerFactory.getLogger(StableConfigSource)
    def listAppender = new ListAppender<ILoggingEvent>()
    listAppender.start()
    logbackLogger.addAppender(listAppender)

    def tempFile = File.createTempFile("testFile_", ".yaml")
    tempFile.text = yaml

    when:
    def stableCfg = new StableConfigSource(tempFile.absolutePath, ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    stableCfg.config == StableConfigSource.StableConfig.EMPTY
    def warnLogs = listAppender.list.findAll { it.level.toString() == 'WARN' }
    warnLogs.any { it.formattedMessage.contains(expectedLogSubstring) }

    cleanup:
    tempFile.delete()
    logbackLogger.detachAppender(listAppender)

    where:
    yaml                                                                                           | expectedLogSubstring
    '''apm_configuration_rules:
          - selectors:
              - key: "someKey"
                matches: ["someValue"]
                operator: equals
            configuration:
              DD_SERVICE: "test"
    '''                                                                                             | "Missing 'origin' in selector"
    '''apm_configuration_rules:
          - selectors:
              - origin: process_arguments
                key: "-Dfoo"
                matches: ["bar"]
                operator: equals
    '''                                                                                             | "Missing 'configuration' in rule"
    '''apm_configuration_rules:
         - configuration:
             DD_SERVICE: "test"
    '''                                                                                             | "Missing 'selectors' in rule"
    '''apm_configuration_rules:
          - selectors: "not-a-list"
            configuration:
              DD_SERVICE: "test"
    '''                                                                                             | "'selectors' must be a list, but got: String"
    '''apm_configuration_rules:
           - selectors:
               - "not-a-map"
             configuration:
               DD_SERVICE: "test"
     '''                                                                                             | "Each selector must be a map, but got: String"
    '''apm_configuration_rules:
          - selectors:
              - origin: process_arguments
                key: "-Dfoo"
                matches: ["bar"]
                operator: equals
            configuration: "not-a-map"
    '''                                                                                             | "'configuration' must be a map, but got: String"
    '''apm_configuration_rules:
          - selectors:
              - origin: process_arguments
                key: "-Dfoo"
                matches: ["bar"]
                operator: equals
            configuration: 12345
    '''                                                                                             | "'configuration' must be a map, but got: Integer"
    '''apm_configuration_rules:
          - "not-a-map"
    '''                                                                                             | "Rule must be a map, but got: String"
    '''apm_configuration_rules:
         - selectors:
             - origin: process_arguments
               key: "-Dfoo"
               matches: "not-a-list"
               operator: equals
           configuration:
             DD_SERVICE: "test"
    '''                                                                                             | "'matches' must be a list, but got: String"
    '''apm_configuration_rules:
         - selectors:
             - origin: process_arguments
               key: "-Dfoo"
               matches: ["bar"]
           configuration:
             DD_SERVICE: "test"
    '''                                                                                            | "Missing 'operator' in selector"
    '''apm_configuration_rules:
         - selectors:
             - origin: process_arguments
               key: "-Dfoo"
               matches: ["bar"]
               operator: 12345
           configuration:
             DD_SERVICE: "test"
    '''                                                                                             | "'operator' must be a string, but got: Integer"
    '''apm_configuration_rules:
          - selectors:
              # origin is missing entirely, should trigger NullPointerException
              - key: "-Dfoo"
                matches: ["bar"]
                operator: equals
    '''                                                                                             | "YAML mapping error in stable configuration file"
  }

  // Corrupt YAML string variable used for testing, defined outside the 'where' block for readability
  static corruptYaml = ''' 
        abc: 123
        def:
          ghi: "jkl"
          lmn: 456
    '''

  // Matching and non-matching Rules used for testing, defined outside the 'where' block for readability
  static sampleMatchingRule = new Rule(Arrays.asList(new Selector("origin", "language", Arrays.asList("Java"), null)), singletonMap("DD_KEY_THREE", "three"))
  static sampleNonMatchingRule = new Rule(Arrays.asList(new Selector("origin", "language", Arrays.asList("Golang"), null)), singletonMap("DD_KEY_FOUR", "four"))

  def writeFileYaml(Path filePath, StableConfig stableConfigs) {
    Map<String, Object> yamlData = new HashMap<>()

    if (stableConfigs.getConfigId() != null && !stableConfigs.getConfigId().isEmpty()) {
      yamlData.put("config_id", stableConfigs.getConfigId())
    }

    if (stableConfigs.getApmConfigurationDefault() != null && !stableConfigs.getApmConfigurationDefault().isEmpty()) {
      yamlData.put("apm_configuration_default", stableConfigs.getApmConfigurationDefault())
    }

    DumpSettings settings = DumpSettings.builder().build()
    Dump dump = new Dump(settings)
    String yamlContent = dump.dumpToString(yamlData)

    try (FileWriter writer = new FileWriter(filePath.toFile())) {
      writer.write(yamlContent)
    }
  }

  def "test config id exists in ConfigCollector when using StableConfigSource"() {
    given:
    Path filePath = Files.createTempFile("testFile_", ".yaml")
    String expectedConfigId = "123"

    // Create YAML content with config_id and some configuration
    def yamlContent = """
config_id: ${expectedConfigId}
apm_configuration_default:
  DD_SERVICE: test-service
  DD_ENV: test-env
"""
    Files.write(filePath, yamlContent.getBytes())

    // Clear any existing collected config
    ConfigCollector.get().collect().clear()

    when:
    // Create StableConfigSource and ConfigProvider
    StableConfigSource stableConfigSource = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)
    ConfigProvider configProvider = new ConfigProvider(stableConfigSource)

    // Trigger config collection by getting a value
    configProvider.getString("SERVICE", "default-service")

    then:
    def collectedConfigs = ConfigCollector.get().collect()
    def serviceSetting = collectedConfigs.get(ConfigOrigin.LOCAL_STABLE_CONFIG).("SERVICE")
    serviceSetting.configId == expectedConfigId
    serviceSetting.value == "test-service"
    serviceSetting.origin == ConfigOrigin.LOCAL_STABLE_CONFIG

    cleanup:
    Files.delete(filePath)
  }

  def writeFileRaw(Path filePath, String configId, String data) {
    data = configId + "\n" + data
    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, data.getBytes(), openOpts)
  }
}
