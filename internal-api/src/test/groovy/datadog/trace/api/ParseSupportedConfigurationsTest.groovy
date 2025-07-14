package datadog.trace.api

import spock.lang.Specification

class ParseSupportedConfigurationsTest extends Specification{

  def "test parsing supportedConfigurations"() {
    when:
    def parseSupportedConfigs = new ParseSupportedConfigurations("supportedConfigurations.json")

    then:
    parseSupportedConfigs.supportedConfigs != null
  }
}
