import com.ibm.as400.resource.ResourceLevel
import datadog.trace.agent.test.InstrumentationSpecification

class InstrumentOldBytecode extends InstrumentationSpecification {
  def "can instrument old bytecode"() {
    expect:
    new ResourceLevel().toString() == "instrumented"
  }
}
