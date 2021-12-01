package datadog.trace.bootstrap.config.provider

import datadog.trace.test.util.DDSpecification

class ConfigConverterTest extends DDSpecification {

  def "Convert boolean properties"() {
    when:
    def value = ConfigConverter.valueOf(stringValue, Boolean)

    then:
    value == expectedConvertedValue

    where:
    stringValue | expectedConvertedValue
    "true"      | true
    "TRUE"      | true
    "True"      | true
    "1"         | true
    "false"     | false
    null        | null
    ""          | null
    "0"         | false
  }
}
