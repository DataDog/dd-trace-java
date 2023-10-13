package datadog.trace.api

import datadog.trace.test.util.DDSpecification

import java.util.concurrent.ConcurrentHashMap
import java.util.function.Function

class GenericClassValueTest extends DDSpecification {

  def "evaluate function" () {
    setup:
    ClassValue<String> cv = GenericClassValue.of(new Function<Class<?>, String>() {
        @Override
        String apply(Class<?> input) {
          return input.getSimpleName()
        }
      })

    expect:
    cv.get(ConcurrentHashMap) == ConcurrentHashMap.simpleName
    cv.get(String) == String.simpleName
  }

  def "constructs produces different instances"() {
    setup:
    ClassValue<String> cv = GenericClassValue.constructing(ConcurrentHashMap)

    expect:
    System.identityHashCode(cv.get(ConcurrentHashMap)) != System.identityHashCode(cv.get(String))
  }
}
