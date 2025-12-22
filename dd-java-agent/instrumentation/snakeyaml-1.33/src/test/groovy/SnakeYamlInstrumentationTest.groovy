import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UntrustedDeserializationModule
import org.yaml.snakeyaml.Yaml

class SnakeYamlInstrumentationTest extends InstrumentationSpecification {

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
    new Yaml().load(inputStream)

    then:
    1 * module.onObject(_)
  }

  void 'test snakeyaml load with a reader'() {
    given:
    final module = Mock(UntrustedDeserializationModule)
    InstrumentationBridge.registerIastModule(module)

    final Reader reader = new StringReader("test")

    when:
    new Yaml().load(reader)

    then:
    1 * module.onObject(_)
  }

  void 'test snakeyaml load with a string'() {
    given:
    final module = Mock(UntrustedDeserializationModule)
    InstrumentationBridge.registerIastModule(module)

    final String string = "test"

    when:
    new Yaml().load(string)

    then:
    1 * module.onObject(_)
  }
}
