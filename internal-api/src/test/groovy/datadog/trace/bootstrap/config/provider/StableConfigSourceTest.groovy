package datadog.trace.bootstrap.config.provider

import datadog.trace.api.ConfigOrigin
import datadog.trace.bootstrap.config.provider.StableConfigYaml.StableConfigYaml
import datadog.trace.test.util.DDSpecification
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.introspector.Property
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import spock.lang.Shared

import java.nio.file.Path
import java.nio.file.Files
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
    when:
    Path filePath = tempFile()
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }
    StableConfigSource config = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    config.getKeys().size() == 0
    config.getConfigId() == null
  }

  def "test file invalid format"() {
    // StableConfigSource must handle the exception thrown by StableConfigParser.parse(filePath) gracefully
    when:
    Path filePath = tempFile()
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }

    try {
      writeFileRaw(filePath, configId, data)
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    stableCfg.getConfigId() == null
    stableCfg.getKeys().size() == 0
    Files.delete(filePath)

    where:
    configId | data
    null     | corruptYaml
    "12345"  | "this is not yaml format!"
  }

  def "test file valid format"() {
    when:
    Path filePath = tempFile()
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }
    StableConfigYaml stableConfigYaml = new StableConfigYaml()
    stableConfigYaml.setConfig_id(configId)
    stableConfigYaml.setApm_configuration_default(defaultConfigs as ConfigurationMap)

    try {
      writeFileYaml(filePath, stableConfigYaml)
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.LOCAL_STABLE_CONFIG)

    then:
    for (key in defaultConfigs.keySet()) {
      String keyString = (String) key
      keyString = keyString.substring(4) // Cut `DD_`
      stableCfg.get(keyString) == defaultConfigs.get(key)
    }
    Files.delete(filePath)

    where:
    // TODO: Add ruleConfigs
    configId | defaultConfigs
    ""       | new HashMap<>()
    "12345"  | new HashMap<>() << ["DD_KEY_ONE": "one", "DD_KEY_TWO": "two"]
  }

  // Corrupt YAML string variable used for testing, defined outside the 'where' block for readability
  @Shared
  def corruptYaml = ''' 
        abc: 123
        def:
          ghi: "jkl"
          lmn: 456
    '''

  static Path tempFile() {
    try {
      return Files.createTempFile("testFile_", ".yaml")
    } catch (IOException e) {
      println "Error creating file: ${e.message}"
      e.printStackTrace()
      return null // or throw new RuntimeException("File creation failed", e)
    }
  }

  def stableConfigYamlWriter = getStableConfigYamlWriter()

  Yaml getStableConfigYamlWriter() {
    DumperOptions options = new DumperOptions()
    // Create the Representer, configure it to omit nulls
    Representer representer = new Representer(options) {
        @Override
        protected NodeTuple representJavaBeanProperty(Object javaBean, Property property, Object propertyValue, Tag customTag) {
          if (propertyValue == null) {
            return null // Skip null values
          } else {
            return super.representJavaBeanProperty(javaBean, property, propertyValue, customTag)
          }
        }
      }
    // Exclude class tag from the resulting yaml string
    representer.addClassTag(StableConfigYaml.class, Tag.MAP)

    // YAML instance with custom Representer
    return new Yaml(representer, options)
  }

  //  def generateYaml(configId, apmConfigurationDefault, apmConfigurationRules, miscData) {
  //    // Construct the YAML dynamically using GString interpolation
  //    def yaml = """
  //config_id: ${configId}
  //apm_configuration_default:
  //    ${apmConfigurationDefault.collect { key, value -> "$key: $value" }.join("\n    ")}
  //apm_configuration_rules:
  //  ${apmConfigurationRules.collect { rule ->
  //      """
  //  - selectors:
  //      ${rule.selectors.collect { selector ->
  //        """
  //      - origin: ${selector.origin}
  //        matches: ${selector.matches.inspect()}
  //        operator: ${selector.operator}
  //          """.stripIndent() }.join("\n      ")}
  //    configuration:
  //        ${rule.configuration.collect { key, value -> "$key: $value" }.join("\n        ")}
  //      """.stripIndent()
  //    }.join("\n")}
  //${miscData}
  //"""
  //    return yaml
  //  }

  def writeFileYaml(Path filePath, StableConfigYaml stableConfigs) {
    //    Yaml yaml = getStableConfigYaml();
    try (FileWriter writer = new FileWriter(filePath.toString())) {
      stableConfigYamlWriter.dump(stableConfigs, writer)
    } catch (IOException e) {
      e.printStackTrace()
    }
  }

  // Use this if you want to explicitly write/test configId
  def writeFileRaw(Path filePath, String configId, String data) {
    data = configId + "\n" + data
    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, data.getBytes(), openOpts)
  }

  // Use this for writing a string directly into a file
  static writeFileRaw(Path filePath, String data) {
    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, data.getBytes(), openOpts)
  }
}
