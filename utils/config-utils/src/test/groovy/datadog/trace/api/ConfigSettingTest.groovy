package datadog.trace.api

import spock.lang.Specification

class ConfigSettingTest extends Specification {

  def "supports equality check"() {
    when:
    def cs1 = ConfigSetting.of(key1, value1, origin1)
    def cs2 = ConfigSetting.of(key2, value2, origin2)

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
    ConfigSetting.of(key, value, ConfigOrigin.DEFAULT).stringValue() == filteredValue

    where:
    key                    | value       | filteredValue
    "DD_API_KEY"           | "somevalue" | "<hidden>"
    "dd.api-key"           | "somevalue" | "<hidden>"
    "dd.profiling.api-key" | "somevalue" | "<hidden>"
    "dd.profiling.apikey"  | "somevalue" | "<hidden>"
    "some.other.key"       | "somevalue" | "somevalue"
  }

  def "support basic types"() {
    expect:
    ConfigSetting.of("key", value, ConfigOrigin.DEFAULT).stringValue() == rendered

    where:
    value          | rendered
    null           | null
    true           | "true"
    false          | "false"
    1              | "1"
    1.0            | "1.0"
    2.33f          | "2.33"
    "string"       | "string"
  }

  def "convert Iterable, Map, and BitSet to String"() {
    expect:
    ConfigSetting.of("key", value, ConfigOrigin.DEFAULT).stringValue() == rendered

    where:
    value                  | rendered
    ["1", "2", "3"]        | "1,2,3"
    [1, 2, 3]              | "1,2,3"
    [1.0f, 22.23d, 3.1415] | "1.0,22.23,3.1415"
    [a: 1, b: 2]           | "a:1,b:2"
    [a: "1", b: "2"]       | "a:1,b:2"
    [:]                    | ""
    []                     | ""
    bitSetIntervals()      | "33,200-300,303,400-500"
  }

  BitSet bitSetIntervals() {
    def bitSetIntervals = new BitSet()
    bitSetIntervals.set(33)
    bitSetIntervals.set(200, 300)
    bitSetIntervals.set(303)
    bitSetIntervals.set(400, 500)
    return bitSetIntervals
  }
}
