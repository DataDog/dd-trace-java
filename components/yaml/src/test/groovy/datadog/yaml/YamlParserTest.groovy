package datadog.yaml

import datadog.trace.bootstrap.config.provider.stableconfigyaml.StableConfigYaml
import datadog.trace.test.util.DDSpecification
import java.nio.file.Path
import datadog.trace.test.util.FileUtils

class YamlParserTest extends DDSpecification{
  def "test parse"() {
    when:
    String yaml = """
    apm_configuration_rules:
      - selectors:
        - origin: language
          matches: ["java"]
          operator: equals
        configuration:
          DD_SERVICE: "${templateVar}"
    """
    Path filePath = FileUtils.tempFile()
    try {
      FileUtils.writeFileRaw(filePath, yaml)
    } catch (IOException e) {
      throw new AssertionError("Failed to write to file: ${e.message}")
    }

    if (envKey != null) {
      injectEnvConfig(envKey, envVal)
    }
    String service
    try {
      def data = YamlParser.parse(filePath as String, StableConfigYaml)
      def configs = data.getApm_configuration_rules().get(0).getConfiguration()
      service = configs.get("DD_SERVICE").toString()
    } catch (IOException e) {
      // parse failed, stable config will be dropped
      service = null
    }

    then:
    service == expect

    where:
    templateVar | envKey | envVal | expect
    "{{environment_variables['DD_KEY']}}" | "DD_KEY" | "value" | "value"
    "{{environment_variables['DD_KEY']}}" | null | null | "UNDEFINED"
    "{{environment_variables['DD_KEY}}" | "DD_KEY" | "value" | "UNDEFINED"
    "{{DD_KEY}}" |  "DD_KEY" | "value" | "UNDEFINED"
    "{{environment_variables['']}}" | null | null | null
    "{{environment_variables['OTHER_KEY']}}" | "DD_KEY" | "value" | "UNDEFINED"
    "{{}}" | null | null | "UNDEFINED"
    "{}" | null | null | "{}"
    "{{environment_variables[DD_KEY]}}" | "DD_KEY" | "value" | "UNDEFINED"
    "{{environment_variables['DD_KEY']}" | null | null | null
    "{{environment_variables['dd_key']}}" | "DD_KEY" | "value" | "value"
  }
}
