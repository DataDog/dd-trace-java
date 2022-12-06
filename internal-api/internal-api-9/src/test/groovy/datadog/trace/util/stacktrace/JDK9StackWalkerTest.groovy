package datadog.trace.util.stacktrace

import datadog.trace.test.util.DDSpecification

import java.util.stream.Collectors

class JDK9StackWalkerTest extends DDSpecification {

  final walker = new JDK9StackWalker()

  def 'stack walker enabled'() {
    when:
    final enabled = walker.isEnabled()

    then:
    enabled
  }

  def 'walk retrieves stackTraceElements'() {
    when:
    final stream = getStackTrace()

    then:
    !stream.empty
  }


  def 'walk retrieves no datadog stack elements'() {
    when:
    final stream = getStackTrace()

    then:
    stream.findAll { it.className.startsWith('datadog') } == []
  }

  List<StackTraceElement> getStackTrace() {
    walker.walk { it.collect(Collectors.toList()) }
  }
}
