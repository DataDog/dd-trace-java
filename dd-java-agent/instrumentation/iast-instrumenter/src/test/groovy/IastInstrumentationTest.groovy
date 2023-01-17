import datadog.trace.agent.tooling.Instrumenter
import datadog.trace.instrumentation.iastinstrumenter.IastInstrumentation
import datadog.trace.test.util.DDSpecification
import net.bytebuddy.description.type.TypeDescription

class IastInstrumentationTest extends DDSpecification{
  def 'test Iast Instrumentation'(){
    given:
    IastInstrumentation instrumentation = new IastInstrumentation()
    TypeDescription typeDescription1 = Mock (TypeDescription)
    typeDescription1.getName() >> "org.jsantos.Tool"
    TypeDescription typeDescription2 = Mock(TypeDescription)
    typeDescription2.getName() >> "oracle.jdbc.Connection"
    Set<Instrumenter.TargetSystem> enabledSystems = Mock(Set)

    when:
    instrumentation.isEnabled()
    instrumentation.isApplicable(enabledSystems)
    instrumentation.callerType()
    boolean description1Matched = instrumentation.callerType().matches(typeDescription1)
    boolean description2Matched = instrumentation.callerType().matches(typeDescription2)

    then:
    description1Matched
    !description2Matched
    noExceptionThrown()
  }
}
