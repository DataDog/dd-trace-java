import com.fasterxml.jackson.core.JsonParser
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.JsonFactoryTestSuite

class JsonFactoryCallSiteTest extends AgentTestRunner {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test createParser(String)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final jsonFactoryTestSuite = new JsonFactoryTestSuite()
    final content = '{"key":"value"}'

    when:
    final result = jsonFactoryTestSuite.createParser(content)

    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, content)
  }

  def 'test createParser(InputStream)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final jsonFactoryTestSuite = new JsonFactoryTestSuite()
    final is = Mock(InputStream)

    when:
    final result = jsonFactoryTestSuite.createParser(is)

    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, is)
  }
}
