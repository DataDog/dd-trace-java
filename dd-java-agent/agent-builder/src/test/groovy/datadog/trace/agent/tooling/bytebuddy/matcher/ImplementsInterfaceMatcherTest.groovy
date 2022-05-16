package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.B
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.E
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.G
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.description.type.TypeList

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named

class ImplementsInterfaceMatcherTest extends AbstractHierarchyMatcherTest {

  def "test matcher #matcherClass.simpleName -> #type.simpleName"() {
    expect:
    implementsInterface(matcher).matches(argument) == result

    where:
    matcherClass | type | result
    A            | A    | false
    A            | B    | false
    B            | A    | false
    A            | E    | false
    A            | F    | true
    A            | G    | true
    F            | A    | false
    F            | F    | false
    F            | G    | false

    matcher = named(matcherClass.name)
    argument = typePool.describe(type.name).resolve()
  }

  def "test exception getting interfaces"() {
    setup:
    def type = Mock(TypeDescription)
    def matcher = implementsInterface(named(Object.name))

    when:
    def result = matcher.matches(type)

    then:
    !result // default to false
    noExceptionThrown()
    1 * type.isInterface() >> false
    1 * type.getInterfaces() >> { throw new Exception("getInterfaces exception") }
    1 * type.getSuperClass() >> { throw new Exception("getSuperClass exception") }
    2 * type.getTypeName() >> "type-name"
    0 * _
  }

  def "test traversal exceptions"() {
    setup:
    def type = Mock(TypeDescription)
    def matcher = implementsInterface(named(Object.name))
    def interfaces = Mock(TypeList.Generic)
    def it = new ThrowOnFirstElement()

    when:
    def result = matcher.matches(type)

    then:
    !result // default to false
    noExceptionThrown()
    1 * type.isInterface() >> false
    1 * type.getInterfaces() >> interfaces
    1 * interfaces.iterator() >> it
    2 * type.getTypeName() >> "type-name"
    1 * type.getSuperClass() >> { throw new Exception("getSuperClass exception") }
    0 * _
  }
}
