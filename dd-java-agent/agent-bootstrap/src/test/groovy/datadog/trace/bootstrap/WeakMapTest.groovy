package datadog.trace.bootstrap

import datadog.trace.api.Function
import spock.lang.Specification

class WeakMapTest extends Specification {

  def supplier = new CounterSupplier()

  def weakMap = new WeakMap.MapAdapter<String, Integer>(new WeakHashMap<>())

  def "getOrCreate a value"() {
    when:
    def count = weakMap.computeIfAbsent('key', supplier)

    then:
    count == 1
    supplier.counter == 1
  }

  def "getOrCreate a value multiple times same class loader same key"() {
    when:
    def count1 = weakMap.computeIfAbsent('key', supplier)
    def count2 = weakMap.computeIfAbsent('key', supplier)

    then:
    count1 == 1
    count2 == 1
    supplier.counter == 1
  }

  def "getOrCreate a value multiple times same class loader different keys"() {
    when:
    def count1 = weakMap.computeIfAbsent('key1', supplier)
    def count2 = weakMap.computeIfAbsent('key2', supplier)

    then:
    count1 == 1
    count2 == 2
    supplier.counter == 2
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
