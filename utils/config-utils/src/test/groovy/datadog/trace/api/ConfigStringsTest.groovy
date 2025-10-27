package datadog.trace.api

import datadog.trace.test.util.DDSpecification
import datadog.trace.util.ConfigStrings

class ConfigStringsTest extends DDSpecification  {


  def "test EnvironmentVariable from propertyName"() {
    expect:
    "DD_FOO_BAR_QUX" == ConfigStrings.propertyNameToEnvironmentVariableName("foo.bar-qux")
    "OTEL_BAR_QUX" == ConfigStrings.propertyNameToEnvironmentVariableName("otel.bar-qux")
  }

  def "test SystemPropertyName from propertyName"() {
    expect:
    "dd.foo.bar-qux" == ConfigStrings.propertyNameToSystemPropertyName("foo.bar-qux")
    "otel.bar-qux" == ConfigStrings.propertyNameToSystemPropertyName("otel.bar-qux")
  }
}
