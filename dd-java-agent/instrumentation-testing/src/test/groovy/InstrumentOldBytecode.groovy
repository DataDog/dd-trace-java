import com.ibm.as400.resource.ResourceLevel
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.config.inversion.ConfigHelper

class InstrumentOldBytecode extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    // Opt out of strict config validation - test module loads test instrumentations with fake names
    ConfigHelper.get().setConfigInversionStrict(ConfigHelper.StrictnessPolicy.TEST)
  }

  def "can instrument old bytecode"() {
    expect:
    new ResourceLevel().toString() == "instrumented"
  }
}
