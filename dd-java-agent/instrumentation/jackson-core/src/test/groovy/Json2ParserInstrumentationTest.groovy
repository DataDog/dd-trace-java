import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.json.JsonOutput

import java.nio.charset.Charset

class Json2ParserInstrumentationTest extends AgentTestRunner {

  private final static String JSON_STRING = '{"root":"root_value","nested":{"nested_array":["array_0","array_1"]}}'

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test json parsing (tainted)'() {
    given:
    final source = new SourceImpl(origin: SourceTypes.REQUEST_BODY, name: 'body', value: JSON_STRING)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    and:
    final reader = new ObjectMapper().readerFor(Map)

    when:
    final taintedResult = reader.readValue(target) as Map

    then:
    JsonOutput.toJson(taintedResult) == JSON_STRING
    _ * module.taintObjectIfTainted(_, _)
    _ * module.findSource(_) >> source
    1 * module.taintString(_, 'root', source.origin, 'root', JSON_STRING)
    1 * module.taintString(_, 'root_value', source.origin, 'root', JSON_STRING)
    1 * module.taintString(_, 'nested', source.origin, 'nested', JSON_STRING)
    1 * module.taintString(_, 'nested_array', source.origin, 'nested_array', JSON_STRING)
    1 * module.taintString(_, 'array_0', source.origin, 'nested_array', JSON_STRING)
    1 * module.taintString(_, 'array_1', source.origin, 'nested_array', JSON_STRING)
    0 * _

    where:
    target << testSuite()
  }

  void 'test json parsing (not tainted)'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    and:
    final reader = new ObjectMapper().readerFor(Map)

    when:
    final taintedResult = reader.readValue(target) as Map

    then:
    JsonOutput.toJson(taintedResult) == JSON_STRING
    _ * module.taintObjectIfTainted(_, _)
    _ * module.findSource(_) >> null
    0 * _

    where:
    target << testSuite()
  }

  private static List<Object> testSuite() {
    return [JSON_STRING, new ByteArrayInputStream(JSON_STRING.getBytes(Charset.defaultCharset()))]
  }

  private static class SourceImpl implements Taintable.Source {
    byte origin
    String name
    String value
  }
}
