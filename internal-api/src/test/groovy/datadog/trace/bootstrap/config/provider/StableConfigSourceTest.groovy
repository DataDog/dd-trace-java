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
    // test empty file
    when:
    Path filePath
    StableConfigSource config
    try {
      filePath = Files.createTempFile("testFile_", ".yaml")
    } catch (IOException e) {
      println "Error creating file: ${e.message}"
      e.printStackTrace()
      return // or throw new RuntimeException("File creation failed", e)
    }
    if (filePath != null) {
      config = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)
    } else {
      return
    }

    then:
    config.getKeys().size() == 0
    config.getConfigId() == null
  }

  def "test get"() {
    when:
    Path filePath = tempFile()
    if (filePath == null) {
      return // fail?
    }

    def configs = new HashMap<>() << ["DD_SERVICE": "svc", "DD_ENV": "env", "CONFIG_NO_DD": "value123"]

    try {
      writeFileYaml(filePath, "12345", configs)
    } catch (IOException e) {
      println "Error writing to file: ${e.message}"
      return // fail?
    }

    StableConfigSource cfg = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)

    then:
    cfg.getKeys().size() == 3
    cfg.get("service") == "svc"
    cfg.get("env") == "env"
    cfg.get("config_no_dd") == null
    cfg.get("config_nonexistent") == null
  }

  def "test file invalid format"() {
    when:
    Path filePath = tempFile()
    if (filePath == null) {
      return // fail?
    }

    try {
      writeFileRaw(filePath, configId, configs)
    } catch (IOException e) {
      println "Error writing to file: ${e.message}"
      return // fail?
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
      return // fail?
    }

    try {
      writeFileYaml(filePath, configId, configs)
    } catch (IOException e) {
      println "Error writing to file: ${e.message}"
      return // fail?
    }

    StableConfigSource stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)

    then:
    stableCfg.getConfigId() == configId
    stableCfg.getKeys().size() == configs.size()
    for (key in stableCfg.getKeys()) {
      key = key.substring(4) // Cut `DD_`
      stableCfg.get(key) == configs.get(key)
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

  def writeFileRaw(Path filePath, String configId, String configs) {
    String data = configId + "\n" + configs
    StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
    Files.write(filePath, data.getBytes(), openOpts)
  }
}
