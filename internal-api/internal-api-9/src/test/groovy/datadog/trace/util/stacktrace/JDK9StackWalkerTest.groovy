package datadog.trace.util.stacktrace

import datadog.trace.test.util.DDSpecification

import java.util.stream.Collectors

class JDK9StackWalkerTest extends DDSpecification {

  def 'stack walker enabled'() {
    given:
    final walker = new JDK9StackWalker()

    when:
    final enabled = walker.isEnabled()

    then:
    enabled
  }

  def 'walk retrieves stackTraceElements'() {
    given:
    final walker = new JDK9StackWalker()

    when:
    final stream = walker.walk { it.collect() }

    then:
    !stream.empty
  }


  def 'walk retrieves no datadog stack elements'() {
    given:
    final walker = new JDK9StackWalker()

    when:
    final stream = walker.walk { it.collect(Collectors.toList()) }

    then:
    stream.findAll { it.className.startsWith('datadog') } == []
  }
}
