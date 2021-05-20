package datadog.trace.util


import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.Platform.isJavaVersionAtLeast

class CollectionUtilsTest extends DDSpecification {


  def "immutable copy of set created when the platform permits"() {
    setup:
    Set<String> set = ["x", "y", "z"]
    String expectedClassName = isJavaVersionAtLeast(10) ? "java.util.ImmutableCollections" : set.getClass().name
    when:
    Set<String> hopefullyImmutable = CollectionUtils.tryMakeImmutableSet(set)
    then:
    hopefullyImmutable.getClass().getName().contains(expectedClassName)
  }

  def "immutable copy of list created when the platform permits"() {
    setup:
    Set<String> set = ["x", "y", "z"]
    String expectedClassName = isJavaVersionAtLeast(10) ? "java.util.ImmutableCollections" : ArrayList.name
    when:
    List<String> hopefullyImmutable = CollectionUtils.tryMakeImmutableList(set)
    then:
    hopefullyImmutable.getClass().getName().contains(expectedClassName)
  }
}
