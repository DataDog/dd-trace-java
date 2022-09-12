package datadog.trace.agent.tooling


import datadog.trace.test.util.DDSpecification
import net.bytebuddy.description.type.TypeDescription

class IastInstrumenterTest extends DDSpecification{
  def 'test Iast Instrumenter'(){
    given:
    IastInstrumenter instrumenter = new IastInstrumenter()
    TypeDescription typeDescription1 = Mock (TypeDescription)
    typeDescription1.getName() >> "org.jsantos.Tool"
    TypeDescription typeDescription2 = Mock(TypeDescription)
    typeDescription2.getName() >> "oracle.jdbc.Connection"
    Set<Instrumenter.TargetSystem> enabledSystems = Mock(Set)

    when:
    instrumenter.isEnabled()
    instrumenter.isApplicable(enabledSystems)
    instrumenter.callerType()
    boolean description1Matched = instrumenter.matches(typeDescription1)
    boolean description2Matched = instrumenter.matches(typeDescription2)

    then:
    description1Matched
    !description2Matched
    noExceptionThrown()
  }
}
