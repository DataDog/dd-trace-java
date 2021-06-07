package datadog.trace.bootstrap

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter
import datadog.trace.test.util.DDSpecification

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.EXECUTOR
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE

class ExcludeFilterTest extends DDSpecification {

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
    excludedTypes.put(EXECUTOR, [yetAnotherName].toSet())
    excludedTypes.put(RUNNABLE_FUTURE, [anotherName, yetAnotherName].toSet())
    ExcludeFilter.add(excludedTypes)
    def anotherExcluded = type == RUNNABLE || type == RUNNABLE_FUTURE
    def yetAnotherExcluded = type == EXECUTOR || type == RUNNABLE_FUTURE

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
