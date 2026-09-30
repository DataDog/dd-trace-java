package datadog.trace.util


import datadog.trace.test.util.DDSpecification

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast
import static datadog.trace.util.CollectionUtils.appendToArray
import static datadog.trace.util.CollectionUtils.arrayContains
import static datadog.trace.util.CollectionUtils.tryMakeImmutableList
import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap
import static datadog.trace.util.CollectionUtils.tryMakeImmutableSet

class CollectionUtilsTest extends DDSpecification {

  def "immutable copy of set created when the platform permits"() {
    setup:
    Set<String> set = ["x", "y", "z"]
    String expectedClassName = isJavaVersionAtLeast(10) ? "java.util.ImmutableCollections" : set.getClass().name
    when:
    Set<String> hopefullyImmutable = tryMakeImmutableSet(set)
    then:
    hopefullyImmutable.getClass().getName().contains(expectedClassName)
  }

  def "immutable copy of list created when the platform permits"() {
    setup:
    Set<String> set = ["x", "y", "z"]
    String expectedClassName = isJavaVersionAtLeast(10) ? "java.util.ImmutableCollections" : ArrayList.name
    when:
    List<String> hopefullyImmutable = tryMakeImmutableList(set)
    then:
    hopefullyImmutable.getClass().getName().contains(expectedClassName)
  }

  def "immutable copy of map created when the platform permits"() {
    setup:
    Map<String, String> map = ["x" : "x", "y" : "y", "z": "z"]
    String expectedClassName = isJavaVersionAtLeast(10) ? "java.util.ImmutableCollections" : map.getClass().name
    when:
    Map<String, String> hopefullyImmutable = tryMakeImmutableMap(map)
    then:
    hopefullyImmutable.getClass().getName().contains(expectedClassName)
  }

  def "appendToArray grows a null array"() {
    when:
    String[] result = appendToArray(null, "a")
    then:
    result == ["a"] as String[]
  }

  def "appendToArray grows an empty array"() {
    when:
    String[] result = appendToArray(new String[0], "a")
    then:
    result == ["a"] as String[]
  }

  def "appendToArray grows a non-empty array"() {
    when:
    String[] result = appendToArray(["a", "b"] as String[], "c")
    then:
    result == ["a", "b", "c"] as String[]
  }

  def "arrayContains tolerates a null array"() {
    expect:
    !arrayContains(null, "a")
  }

  def "arrayContains reports whether the value is present"() {
    expect:
    arrayContains(["a", "b"] as String[], "a")
    !arrayContains(["a", "b"] as String[], "c")
  }
}
