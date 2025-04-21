package datadog.yaml

import datadog.trace.test.util.DDSpecification
import java.nio.file.Path
import datadog.trace.test.util.FileUtils

class YamlParserTest extends DDSpecification{
  def "test parse"() {
    //    when:
    //    String yaml = """
    //apm_configuration_rules:
    //  - selectors:
    //    - origin: language
    //      matches: ["java"]
    //      operator: equals
    //    configuration:
    //      DD_SERVICE: "${templateVar}"
    //"""
    //    Path filePath = FileUtils.tempFile()
    //    try {
    //      FileUtils.writeFileRaw(filePath, yaml)
    //    } catch (IOException e) {
    //      throw new AssertionError("Failed to write to file: ${e.message}")
    //    }
    //    where:
    //    templateVar | envKey | envVal | expect
    //    "{{environment_variables['DD_KEY']}}" | "DD_KEY" | "value" | "value"
  }
}
