import com.ibm.as400.resource.ResourceLevel
import stackstate.trace.agent.test.AgentTestRunner

class InstrumentOldBytecode extends AgentTestRunner {
  def "can instrument old bytecode"() {
    expect:
    new ResourceLevel().toString() == "instrumented"
  }
}
