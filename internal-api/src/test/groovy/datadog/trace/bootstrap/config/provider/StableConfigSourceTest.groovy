package datadog.trace.bootstrap.config.provider

import datadog.trace.api.ConfigOrigin
import datadog.trace.test.util.DDSpecification
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

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
    // How to check that the "file does not exist" error was logged?
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

  def "test populated file"() {
    when:
    Path filePath
    StableConfigSource stableCfg
    try {
      filePath = Files.createTempFile("testFile_", ".yaml")
    } catch (IOException e) {
      println "Error creating file: ${e.message}"
      e.printStackTrace()
      return // or throw new RuntimeException("File creation failed", e)
    }
    if (filePath == null) {
      return
    }

    DumperOptions options = new DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)

    // Prepare to write the data map to the file in yaml format
    Yaml yaml = new Yaml(options)
    String yamlString
    if (corrupt == true) {
      yamlString = '''
      abc:   123
      def
        ghi "jkl"
        lmn: 456
      '''
    } else {
      Map<String, Object> data = new HashMap<>()
      if (configId != null) {
        data.put("config_id", configId)
      }
      if (configs != null) {
        data.put("apm_configuration_default", configs)
      }

      yamlString = yaml.dump(data)
    }

    try {
      StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
      Files.write(filePath, yamlString.getBytes(), openOpts)
      println "YAML written to: $filePath"
    } catch (IOException e) {
      println "Error writing to file: ${e.message}"
      // fail fast?
    }

    stableCfg = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)

    then:
    stableCfg.getConfigId() == configId
    if (configs == null) {
      stableCfg.getKeys().size() == 0
    } else {
      stableCfg.getKeys().size() == configs.size()
    }
    Files.delete(filePath)

    where:
    key_one = "key_one"
    val_one = "val_one"
    key_two = "key_two"
    val_two = "val_2"
    configId | configs | corrupt
    null        | null | true
    ""    | new HashMap<>() | false
    "12345" | new HashMap<>() << ["key_one": "val_one", "key_two": "val_two"] | false

  }
}
