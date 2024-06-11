package com.datadog.appsec.util

import datadog.trace.test.util.DDSpecification

class ObjectFlattenerSpecification extends DDSpecification {

  def "flatten should return null for null input"() {
    expect:
    ObjectFlattener.flatten(null) == null
  }

  def "flatten should return primitive types as is"() {
    expect:
    ObjectFlattener.flatten(42) == 42
    ObjectFlattener.flatten("test") == "test"
    ObjectFlattener.flatten(true) == true
    //        ObjectFlattener.flatten(3.14) == 3.14
  }

  def "flatten should return collections with flattened elements"() {
    given:
    def list = [1, "test", [nested: "value"]] as List

    when:
    def result = ObjectFlattener.flatten(list)

    then:
    result instanceof List
    result.size() == 3
    result[0] == 1
    result[1] == "test"
    result[2] == [nested: "value"]
  }

  def "flatten should return maps with flattened values"() {
    given:
    def map = [key1: 1, key2: "test", key3: [nested: "value"]] as Map

    when:
    def result = ObjectFlattener.flatten(map)

    then:
    result instanceof Map
    result.size() == 3
    result.key1 == 1
    result.key2 == "test"
    result.key3 == [nested: "value"]
  }

  def "flatten should return custom objects as map"() {
    given:
    def nestedObject = new NestedObject("NestedName", 456)
    def testObject = new TestObject(name: "TestName", value: 123, nestedObject: nestedObject)

    when:
    def result = ObjectFlattener.flatten(testObject)

    then:
    result instanceof Map
    result.name == "TestName"
    result.value == 123
    result.nestedObject instanceof Map
    result.nestedObject.nestedName == "NestedName"
    result.nestedObject.nestedValue == 456
  }

  def "flatten should handle nested collections and maps"() {
    given:
    def nestedMap = [key1: [nestedKey: "nestedValue"]] as Map
    def nestedList = [1, [2, 3], ["nested": "value"]] as List
    def testObject = new TestObject(name: "TestName", value: 123, nestedObject: new NestedObject("NestedName", 456))
    testObject.setList(nestedList)
    testObject.setMap(nestedMap)

    when:
    def result = ObjectFlattener.flatten(testObject)

    then:
    result instanceof Map
    result.name == "TestName"
    result.value == 123
    result.nestedObject instanceof Map
    result.nestedObject.nestedName == "NestedName"
    result.nestedObject.nestedValue == 456
    result.list instanceof List
    result.list.size() == 3
    result.list[0] == 1
    result.list[1] == [2, 3]
    result.list[2] == ["nested": "value"]
    result.map instanceof Map
    result.map.key1 == [nestedKey: "nestedValue"]
  }

  private static class TestObject {
    String name
    int value
    NestedObject nestedObject
    List<Object> list
    Map<String, Object> map
  }

  private static class NestedObject {
    String nestedName
    int nestedValue

    NestedObject(String nestedName, int nestedValue) {
      this.nestedName = nestedName
      this.nestedValue = nestedValue
    }
  }
}
