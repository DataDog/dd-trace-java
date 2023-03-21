import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import foo.bar.JsonFactoryTestSuite
import groovy.transform.CompileDynamic

@CompileDynamic
class JsonFactoryInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test createParser(String)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final jsonFactoryTestSuite = new JsonFactoryTestSuite(factory)
    final content = '{"key":"value"}'

    when:
    final result = jsonFactoryTestSuite.createParser(content)

    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, content)

    where:
    factory           | _
    new JsonFactory() | _
  }

  void 'test createParser(InputStream)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final jsonFactoryTestSuite = new JsonFactoryTestSuite(factory)
    final is = Mock(InputStream)

    when:
    final result = jsonFactoryTestSuite.createParser(is)

    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, is)

    where:
    factory           | _
    new JsonFactory() | _
  }

  void 'test createParser(Reader)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final jsonFactoryTestSuite = new JsonFactoryTestSuite(factory)
    final reader = Mock(Reader)

    when:
    final result = jsonFactoryTestSuite.createParser(reader)

    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, reader)

    where:
    factory           | _
    new JsonFactory() | _
  }
}
