import com.datadog.iast.test.IastAgentTestRunner
import datadog.trace.api.iast.IastContext
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.Taintable.Source
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.json.JsonOutput
import org.codehaus.jackson.JsonParser
import org.codehaus.jackson.map.ObjectMapper

class Json1ParserInstrumentationTest extends IastAgentTestRunner {

  private final static String JSON_STRING = '{"root":"root_value","nested":{"nested_array":["array_0","array_1"]}}'

  void 'test json parsing (tainted)'() {
    given:
    final source = new SourceImpl(origin: SourceTypes.REQUEST_BODY, name: 'body', value: JSON_STRING)
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    and:
    final reader = new ObjectMapper().reader(Map)

    when:
    final taintedResult = computeUnderIastTrace {
      reader.readValue(target) as Map
    }

    then:
    JsonOutput.toJson(taintedResult) == JSON_STRING
    _ * module.taintIfTainted(_ as IastContext, _ as JsonParser, _)
    _ * module.findSource(_ as IastContext, _ as JsonParser) >> source
    1 * module.taint(_ as IastContext, 'root', source.origin, 'root', JSON_STRING)
    1 * module.taint(_ as IastContext, 'root_value', source.origin, 'root', JSON_STRING)
    1 * module.taint(_ as IastContext, 'nested', source.origin, 'nested', JSON_STRING)
    1 * module.taint(_ as IastContext, 'nested_array', source.origin, 'nested_array', JSON_STRING)
    1 * module.taint(_ as IastContext, 'array_0', source.origin, 'nested_array', JSON_STRING)
    1 * module.taint(_ as IastContext, 'array_1', source.origin, 'nested_array', JSON_STRING)
    0 * _

    where:
    target << testSuite()
  }

  void 'test json parsing (not tainted)'() {
    given:
    final module = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(module)

    and:
    final reader = new ObjectMapper().reader(Map)

    when:
    final taintedResult = computeUnderIastTrace { reader.readValue(target) as Map }

    then:
    JsonOutput.toJson(taintedResult) == JSON_STRING
    _ * module.taintIfTainted(_ as IastContext, _ as JsonParser, _)
    _ * module.findSource(_ as IastContext, _ as JsonParser) >> null
    0 * _

    where:
    target << testSuite()
  }

  private static List<Object> testSuite() {
    return [JSON_STRING]
  }

  private static class SourceImpl implements Source {
    byte origin
    String name
    String value
  }
}
