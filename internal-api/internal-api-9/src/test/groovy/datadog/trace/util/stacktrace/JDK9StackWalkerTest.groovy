package datadog.trace.util.stacktrace


import spock.lang.Specification

class JDK9StackWalkerTest extends Specification {

  def "stack walker enabled"() {
    when:
    def walker = new JDK9StackWalker()

    then:
    walker.isEnabled()
  }
}
