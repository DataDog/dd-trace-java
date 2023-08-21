import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.JsonParser

class Json1FactoryInstrumentationTest extends AgentTestRunner {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test createParser(String)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final content = '{"key":"value"}'

    when:
    final result = new JsonFactory().createJsonParser(content)

    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, content)
    0 * _
  }

  void 'test createParser(InputStream)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final is = Mock(InputStream)

    when:
    final result = new JsonFactory().createJsonParser(is)

    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, is)
    2 * is.read(_,_,_)
    0 * _
  }

  void 'test createParser(Reader)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final reader = Mock(Reader)

    when:
    final result = new JsonFactory().createJsonParser(reader)


    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, reader)
    0 * _
  }
}
