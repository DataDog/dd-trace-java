package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter
import datadog.trace.test.util.DDSpecification
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

  def "test named"() {
    setup:
    def named = Mock(NamedElement)
    named.getActualName() >> { name }
    def matcher = NameMatchers.named("foo")

    when:
    def result = matcher.matches(named)

    then:
    result == expected

    where:
    name  | expected
    "foo" | true
    "bar" | false
  }

  def "test nameStartsWith"() {
    setup:
    def named = Mock(NamedElement)
    named.getActualName() >> { name }
    def matcher = NameMatchers.nameStartsWith("foo")

    when:
    def result = matcher.matches(named)

    then:
    result == expected

    where:
    name      | expected
    "foo"     | true
    "food"    | true
    "barfood" | false
  }

  def "test nameEndsWith"() {
    setup:
    def named = Mock(NamedElement)
    named.getActualName() >> { name }
    def matcher = NameMatchers.nameEndsWith("foo")

    when:
    def result = matcher.matches(named)

    then:
    result == expected

    where:
    name    | expected
    "tofoo" | true
    "tofu"  | false
  }

  def "test notExcludedByName"() {
    def named = Mock(NamedElement)
    def name = "java.util.concurrent.ConcurrentHashMap\$Foo"
    named.getActualName() >> { name }
    when:
    def fjtExcluder = NameMatchers.notExcludedByName(ExcludeFilter.ExcludeType.FORK_JOIN_TASK)
    then:
    !fjtExcluder.matches(named)

    when:
    def executorExcluder = NameMatchers.notExcludedByName(ExcludeFilter.ExcludeType.EXECUTOR)
    then:
    executorExcluder.matches(named)
  }
}
