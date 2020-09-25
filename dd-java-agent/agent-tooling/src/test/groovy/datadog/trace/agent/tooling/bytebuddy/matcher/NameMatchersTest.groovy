package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.util.test.DDSpecification
import net.bytebuddy.description.NamedElement

class NameMatchersTest extends DDSpecification {

  def "test namedOneOf"() {
    setup:
    def named = Mock(NamedElement)
    named.getActualName() >> { name }
    def matcher = NameMatchers.namedOneOf("foo", "bar")

    when:
    def result = matcher.matches(named)

    then:
    result == expected

    where:
    name      | expected
    "foo"     | true
    "bar"     | true
    "missing" | false
  }


  def "test namedNoneOf"() {
    setup:
    def named = Mock(NamedElement)
    named.getActualName() >> { name }
    def matcher = NameMatchers.namedNoneOf("foo", "bar")

    when:
    def result = matcher.matches(named)

    then:
    result == expected

    where:
    name      | expected
    "foo"     | false
    "bar"     | false
    "missing" | true
  }
}
