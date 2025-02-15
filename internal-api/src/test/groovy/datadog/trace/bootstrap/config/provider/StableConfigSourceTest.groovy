package datadog.trace.bootstrap.config.provider

import datadog.trace.api.ConfigOrigin
import datadog.trace.test.util.DDSpecification
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import spock.lang.Shared

import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class StableConfigSourceTest extends DDSpecification {

  def "test file doesn't exist"() {
    setup:
    StableConfigSource config = new StableConfigSource(StableConfigSource.USER_STABLE_CONFIG_PATH, ConfigOrigin.USER_STABLE_CONFIG)

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
    StableConfigSource config = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)

    then:
    config.getKeys().size() == 0
    config.getConfigId() == null
  }

  def "test get"() {
    when:
    Path filePath = tempFile()
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }

    def configs = new HashMap<>() << ["DD_SERVICE": "svc", "DD_ENV": "env", "CONFIG_NO_DD": "value123"]

    try {
      writeFileYaml(filePath, "12345", configs)
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource cfg = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)

    then:
    cfg.get("service") == "svc"
    cfg.get("env") == "env"
    cfg.get("config_no_dd") == null
    cfg.get("config_nonexistent") == null
    cfg.getKeys().size() == 3
    cfg.getConfigId() == "12345"
    Files.delete(filePath)
  }

  def "test file invalid format"() {
    when:
    Path filePath = tempFile()
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }

    try {
      writeFileRaw(filePath, configId, configs)
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)

    then:
    stableCfg.getConfigId() == null
    stableCfg.getKeys().size() == 0
    Files.delete(filePath)

    where:
    configId | configs
    null     | corruptYaml
    "12345"  | "this is not yaml format!"
  }

  def "test file valid format"() {
    when:
    Path filePath = tempFile()
    if (filePath == null) {
      throw new AssertionError("Failed to create test file")
    }

    try {
      writeFileYaml(filePath, configId, configs)
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)

    then:
    for (key in configs.keySet()) {
      String keyString = (String) key
      keyString = keyString.substring(4) // Cut `DD_`
      stableCfg.get(keyString) == configs.get(key)
    }
    Files.delete(filePath)

    where:
    configId | configs
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

  static writeFileYaml(Path filePath, String configId, Map configs) {
    DumperOptions options = new DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)

    // Prepare to write the data map to the file in yaml format
    Yaml yaml = new Yaml(options)
    String yamlString
    Map<String, Object> data = new HashMap<>()
    if (configId != null) {
      data.put("config_id", configId)
    }
    if (configs instanceof HashMap<?, ?>) {
      data.put("apm_configuration_default", configs)
    }

    yamlString = yaml.dump(data)

    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, yamlString.getBytes(), openOpts)
  }

  // Use this if you want to explicitly write/test configId
  def writeFileRaw(Path filePath, String configId, String configs) {
    String data = configId + "\n" + configs
    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, data.getBytes(), openOpts)
  }

  static writeFileRaw(Path filePath, String data) {
    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, data.getBytes(), openOpts)
  }
}
