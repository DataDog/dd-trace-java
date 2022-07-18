package datadog.trace.util.stacktrace

import datadog.trace.test.util.DDSpecification

import java.util.stream.Stream

class JDK9StackWalkerTest extends DDSpecification {

  def "stack walker enabled"() {
    given:
    def walker = new JDK9StackWalker()
    when:
    boolean enabled = walker.isEnabled()

    then:
    enabled
  }

  def "walk retrieves stackTraceElements"() {
    given:
    def walker = new JDK9StackWalker()

    when:
    Stream<StackTraceElement> stream = walker.walk()

    then:
    stream.count() != 0
  }


  def "walk retrieves no datadog stack elements"() {
    given:
    def walker = new JDK9StackWalker()

    when:
    Stream<StackTraceElement> stream = walker.walk()

    then:
    stream.filter({ e -> e.getClassName().startsWith("datadog") }).count() == 0
  }
}
