package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.agent.tooling.AgentTooling
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.A
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.B
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.F
import datadog.trace.agent.tooling.bytebuddy.matcher.testclasses.G
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.jar.asm.Opcodes
import spock.lang.Shared

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named

class ExtendsClassMatcherTest extends DDSpecification {
  @Shared
  def typePool =
  AgentTooling.poolStrategy()
  .typePool(AgentTooling.locationStrategy().classFileLocator(this.class.classLoader, null), this.class.classLoader)

  def "test matcher #matcherClass.simpleName -> #type.simpleName"() {
    expect:
    extendsClass(matcher).matches(argument) == result

    where:
    matcherClass | type | result
    A            | B    | false
    A            | F    | false
    G            | F    | false
    F            | F    | true
    F            | G    | true

    matcher = named(matcherClass.name)
    argument = typePool.describe(type.name).resolve()
  }

  def "test traversal exceptions"() {
    setup:
    def type = Mock(TypeDescription)
    def matcher = extendsClass(named(Object.name))

    when:
    def result = matcher.matches(type)

    then:
    !result // default to false
    noExceptionThrown()
    1 * type.getModifiers() >> Opcodes.ACC_ABSTRACT
    1 * type.asErasure() >> type
    1 * type.getTypeName() >> "type-name"
    1 * type.getActualName() >> "type-name"
    1 * type.getSuperClass() >> { throw new Exception("getSuperClass exception") }
    0 * _
  }
}
