package datadog.trace.api

import spock.lang.Specification

class ConfigSettingTest extends Specification {

  def "supports equality check"() {
    when:
    def cs1 = new ConfigSetting(key1, value1, origin1)
    def cs2 = new ConfigSetting(key2, value2, origin2)

    then:
    if (key1 == key2 && value1 == value2 && origin1 == origin2) {
      assert cs1.hashCode() == cs2.hashCode()
      assert cs1 == cs2
      assert cs2 == cs1
      assert cs1.toString() == cs2.toString()
    } else {
      assert cs1.hashCode() != cs2.hashCode()
      assert cs1 != cs2
      assert cs2 != cs1
      assert cs1.toString() != cs2.toString()
    }

    where:
    key1  | key2   | value1   | value2  | origin1               | origin2
    "key" | "key"  | "value"  | "value" | ConfigOrigin.DEFAULT  | ConfigOrigin.DEFAULT
    "key" | "key2" | "value"  | "value" | ConfigOrigin.ENV      | ConfigOrigin.ENV
    "key" | "key"  | "value2" | "value" | ConfigOrigin.JVM_PROP | ConfigOrigin.JVM_PROP
    "key" | "key"  | "value"  | "value" | ConfigOrigin.ENV      | ConfigOrigin.DEFAULT
  }

  def "filters key values"() {
    expect:
    new ConfigSetting(key, value, ConfigOrigin.DEFAULT).value == filteredValue

    where:
    key                    | value       | filteredValue
    "DD_API_KEY"           | "somevalue" | "<hidden>"
    "dd.api-key"           | "somevalue" | "<hidden>"
    "dd.profiling.api-key" | "somevalue" | "<hidden>"
    "dd.profiling.apikey"  | "somevalue" | "<hidden>"
    "some.other.key"       | "somevalue" | "somevalue"
  }
}
