import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UntrustedDeserializationModule
import foo.bar.TestSnakeYamlSuite

class SnakeYamlInstrumenterTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  void 'test snakeyaml load with an input stream'() {
    given:
    final module = Mock(UntrustedDeserializationModule)
    InstrumentationBridge.registerIastModule(module)

    final InputStream inputStream = new ByteArrayInputStream("test".getBytes())

    when:
    TestSnakeYamlSuite.init(inputStream)

    then:
    1 * module.onObject(_)
  }

  void 'test snakeyaml load with a reader'() {
    given:
    final module = Mock(UntrustedDeserializationModule)
    InstrumentationBridge.registerIastModule(module)

    final Reader reader = new StringReader("test")

    when:
    TestSnakeYamlSuite.init(reader)

    then:
    1 * module.onObject(_)
  }

  void 'test snakeyaml load with a string'() {
    given:
    final module = Mock(UntrustedDeserializationModule)
    InstrumentationBridge.registerIastModule(module)

    final String string = "test"

    when:
    TestSnakeYamlSuite.init(string)

    then:
    1 * module.onObject(_)
  }
}
