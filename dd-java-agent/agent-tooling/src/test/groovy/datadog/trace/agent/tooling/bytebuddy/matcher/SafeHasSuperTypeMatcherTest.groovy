package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.agent.tooling.AgentTooling
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.B
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.E
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.G
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.description.type.TypeList
import net.bytebuddy.jar.asm.Opcodes
import spock.lang.Shared

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named

class SafeHasSuperTypeMatcherTest extends DDSpecification {
  @Shared
  def typePool =
  AgentTooling.poolStrategy()
  .typePool(AgentTooling.locationStrategy().classFileLocator(this.class.classLoader, null), this.class.classLoader)

  def "test matcher #matcherClass.simpleName -> #type.simpleName"() {
    expect:
    safeHasSuperType(matcher).matches(argument) == result

    where:
    matcherClass | type | result
    A            | A    | false
    A            | B    | false
    B            | A    | false
    A            | E    | false
    A            | F    | true
    B            | G    | true
    F            | A    | false
    F            | F    | true
    F            | G    | true

    matcher = named(matcherClass.name)
    argument = typePool.describe(type.name).resolve()
  }

  def "test exception getting interfaces"() {
    setup:
    def type = Mock(TypeDescription)
    def erasureType = Mock(TypeDescription)
    def matcher = safeHasSuperType(named(Object.name))

    when:
    def result = matcher.matches(type)

    then:
    !result // default to false
    noExceptionThrown()
    1 * type.getModifiers() >> Opcodes.ACC_ABSTRACT
    1 * type.getInterfaces() >> { throw new Exception("getInterfaces exception") }
    1 * type.getSuperClass() >> { throw new Exception("getSuperClass exception") }
    1 * type.asErasure() >> erasureType
    1 * erasureType.getActualName() >> "erasureType"
    2 * type.getTypeName() >> "type-name"
    0 * _
  }

  def "test traversal exceptions"() {
    setup:
    def type = Mock(TypeDescription)
    def matcher = safeHasSuperType(named(Object.name))
    def interfaces = Mock(TypeList.Generic)
    def it = new ThrowOnFirstElement()

    when:
    def result = matcher.matches(type)

    then:
    !result // default to false
    noExceptionThrown()
    1 * type.getModifiers() >> Opcodes.ACC_ABSTRACT
    1 * type.getInterfaces() >> interfaces
    1 * interfaces.iterator() >> it
    1 * type.asErasure() >> { throw new Exception("asErasure exception") }
    2 * type.getTypeName() >> "type-name"
  }
}
