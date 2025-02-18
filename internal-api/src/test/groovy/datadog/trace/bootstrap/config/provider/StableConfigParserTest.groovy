package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification

import java.nio.file.Files
import java.nio.file.Path

class StableConfigParserTest extends DDSpecification {

  def "test parser"() {
    when:
    Path filePath = StableConfigSourceTest.tempFile()
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
      StableConfigSourceTest.writeFileRaw(filePath, yaml)
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
    keys.size() == 5
    !keys.contains("something-irrelevant")
    !keys.contains("something-else-irrelevant")
    cfg.getConfigId().trim() == ("12345")
    cfg.get("KEY_ONE") == "value_one"
    cfg.get("KEY_TWO") == "value_two"
    cfg.get("KEY_THREE") == "100"
    cfg.get("KEY_FOUR") == "true"
    cfg.get("KEY_FIVE") == "[a,b,c,d]"
    Files.delete(filePath)
  }
}
