package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification

import java.nio.file.Path

class StableConfigParserTest extends DDSpecification {

  def "test parser"() {
    when:
    Path filePath = StableConfigSourceTest.tempFile()
    if (filePath == null) {
      throw new AssertionError("Failed to create test file: ${e.message}")
    }
    HashMap<String, Object> configs = new HashMap<>()
    configs.put("KEY_ONE", "VALUE_ONE")
    configs.put("KEY_TWO", "VALUE_TWO")
    configs.put("KEY_THREE", "VALUE_THREE")

    HashMap<String, Object> fileContent = new HashMap<>()
    fileContent.put("something-irrelevant-to-apm", "")
    fileContent.put("config_id", "12345")
    fileContent.put("something-else-irrelevant", "value-irrelevant")
    fileContent.put("apm_configuration_default", configs)

    try {
      StableConfigSourceTest.writeFileYaml(filePath, fileContent)
    } catch (IOException e) {
      println "Error writing to file: ${e.message}"
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
    !keys.contains("something-irrelevant-to-apm")
    !keys.contains("something-else-irrelevant")
    cfg.getConfigId().trim() == ("12345")
    cfg.get("KEY_ONE") == "VALUE_ONE"
    cfg.get("KEY_TWO") == "VALUE_TWO"
    cfg.get("KEY_THREE") == "VALUE_THREE"
  }
}
