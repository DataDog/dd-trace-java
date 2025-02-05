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
    // How to check that the "file does not exist" error was logged?
  }

  def "test valid file"() {
    // test empty file
    when:
    Path filePath = null
    StableConfigSource config = null
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

    // test populated file
    when:
    def key1 = "dd_first_key"
    def val1 = "dd_first_val"
    def key2 = "dd_second_key"
    def val2 = "dd_second_val"
    // Create the map that will be used to populate the config file
    Map<String, Object> data = new HashMap<>()
    data.put("apm_configuration_default", new HashMap<String, Object>() {{
          put(key1, val1)
          put(key2, val2)
        }})

    DumperOptions options = new DumperOptions()
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)

    // Prepare to write the data map to the file in yaml format
    Yaml yaml = new Yaml(options)
    String yamlString = yaml.dump(data)

    try {
      StandardOpenOption[] openOpts = [StandardOpenOption.WRITE] as StandardOpenOption[]
      Files.write(filePath, yamlString.getBytes(), openOpts)
      println "YAML written to: $filePath"
    } catch (IOException e) {
      println "Error writing to file: ${e.message}"
      // fail fast?
    }

    then:
    StableConfigSource config2 = new StableConfigSource(filePath.toString(), ConfigOrigin.USER_STABLE_CONFIG)
    config2.getKeys().size() == 2
    config2.get(key1) == val1
    config2.get(key2) == val2
    Files.delete(filePath)
  }
}
