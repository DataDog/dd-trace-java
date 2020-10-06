package datadog.trace.bootstrap

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.*

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter
import spock.lang.Specification

class ExcludeFilterTest extends Specification {

  def "test empty ExcludeFilter"() {
    setup:
    def one = new One()
    def oneName = One.getName()

    expect:
    !ExcludeFilter.exclude(type, one)
    !ExcludeFilter.exclude(type, oneName)

    where:
    type << ExcludeFilter.ExcludeType.values()
  }

  def "test ExcludeFilter"() {
    setup:
    def another = new Another()
    def anotherName = Another.getName()
    def yetAnother = new YetAnother()
    def yetAnotherName = YetAnother.getName()
    Map<ExcludeFilter.ExcludeType, Set<String>> excludedTypes = new HashMap<>()
    excludedTypes.put(RUNNABLE, [anotherName].toSet())
    excludedTypes.put(CALLABLE, [yetAnotherName].toSet())
    excludedTypes.put(FUTURE, [anotherName, yetAnotherName].toSet())
    ExcludeFilter.add(excludedTypes)
    def anotherExcluded = type == RUNNABLE || type == FUTURE
    def yetAnotherExcluded = type == CALLABLE || type == FUTURE

    expect:
    ExcludeFilter.exclude(type, another) == anotherExcluded
    ExcludeFilter.exclude(type, anotherName) == anotherExcluded
    ExcludeFilter.exclude(type, yetAnother) == yetAnotherExcluded
    ExcludeFilter.exclude(type, yetAnotherName) == yetAnotherExcluded

    where:
    type << ExcludeFilter.ExcludeType.values()
  }

  static class One {}

  static class Another {}

  static class YetAnother {}
}
