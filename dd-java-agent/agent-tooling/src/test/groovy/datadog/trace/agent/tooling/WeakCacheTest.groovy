package datadog.trace.agent.tooling

import datadog.trace.api.Function
import spock.lang.Specification

class WeakCacheTest extends Specification {
  def supplier = new CounterSupplier()

  def weakCache = AgentTooling.newWeakCache()

  def "computeIfAbsent a value"() {
    when:
    def count = weakCache.computeIfAbsent('key', supplier)

    then:
    count == 1
    supplier.counter == 1
    weakCache.getIfPresent('key') == 1
  }

  def "computeIfAbsent a value multiple times same class loader same key"() {
    when:
    def count1 = weakCache.computeIfAbsent('key', supplier)
    def count2 = weakCache.computeIfAbsent('key', supplier)

    then:
    count1 == 1
    count2 == 1
    supplier.counter == 1
    weakCache.getIfPresent('key') == 1
  }

  def "computeIfAbsent a value multiple times same class loader different keys"() {
    when:
    def count1 = weakCache.computeIfAbsent('key1', supplier)
    def count2 = weakCache.computeIfAbsent('key2', supplier)

    then:
    count1 == 1
    count2 == 2
    supplier.counter == 2
    weakCache.getIfPresent('key1') == 1
    weakCache.getIfPresent('key2') == 2
  }

  def "max size check"() {
    setup:
    def weakCacheFor1elem = AgentTooling.newWeakCache(1)

    when:
    def valBefore = weakCacheFor1elem.getIfPresent("key1")
    def valAfterGet = weakCacheFor1elem.computeIfAbsent("key1", supplier)

    then:
    valBefore == null
    valAfterGet == 1

    when:
    weakCacheFor1elem.put("key1", 42)

    then:
    weakCacheFor1elem.computeIfAbsent("key1", supplier) == 42

    when:
    def valByKey2 = weakCacheFor1elem.computeIfAbsent("key2", supplier)

    then:
    valByKey2 == 2

    // The following check doesn't work because caches are allowed to temporarily exceed the max size
    //    weakCacheFor1elem.getIfPresent("key1") == null
  }

  class CounterSupplier implements Function<String, Integer> {
    def counter = 0

    @Override
    Integer apply(String ignored) {
      counter = counter + 1
      return counter
    }
  }
}
