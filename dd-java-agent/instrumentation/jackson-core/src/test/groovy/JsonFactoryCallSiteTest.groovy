import com.fasterxml.jackson.core.JsonParser
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.JacksonModule
import foo.bar.JsonFactoryTestSuite

class JsonFactoryCallSiteTest extends AgentTestRunner {


  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test createParser(String)'() {
    setup:
    final jacksonModule = Mock(JacksonModule)
    InstrumentationBridge.registerIastModule(jacksonModule)
    final jsonFactoryTestSuite = new JsonFactoryTestSuite()
    final content = '{"key":"value"}'

    when:
    final result = jsonFactoryTestSuite.createParser(content)

    then:
    result != null
    1 * jacksonModule.onJsonFactoryCreateParser(content, _ as JsonParser)
  }

  def 'test createParser(InputStream)'() {
    setup:
    final jacksonModule = Mock(JacksonModule)
    InstrumentationBridge.registerIastModule(jacksonModule)
    final jsonFactoryTestSuite = new JsonFactoryTestSuite()
    final is = new ByteArrayInputStream('{"key":"value"}'.getBytes())

    when:
    final result = jsonFactoryTestSuite.createParser(is)

    then:
    result != null
    1 * jacksonModule.onJsonFactoryCreateParser(is, _ as JsonParser)
  }
}
