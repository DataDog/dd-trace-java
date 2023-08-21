package datadog.trace.instrumentation.jersey

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.source.WebModule
import foo.bar.TestSuite

class AbstractStringReaderTest extends AgentTestRunner {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'ParamConverter test'(){
    setup:
    WebModule iastModule = Mock(WebModule)
    InstrumentationBridge.registerIastModule(iastModule)

    when:
    TestSuite.convertString("Pepe")

    then:
    1 * iastModule.onParameterValue(null, "Pepe")
  }
}

