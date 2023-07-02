import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.JsonParser
import org.codehaus.jackson.JsonToken

class Json1ParserInstrumentationTest extends AgentTestRunner {

  private final static String JSON_STRING = '{"key1":"value1","key2":"value2","key3":"value3"}'

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test getCurrentName()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    skipUntil(jsonParser, JsonToken.FIELD_NAME)

    when:
    final result = jsonParser.getCurrentName()

    then:
    result == 'key1'
    1 * propagationModule.taintIfInputIsTainted('key1', jsonParser)
    0 * _

    where:
    jsonParser << [
      new JsonFactory().createJsonParser(JSON_STRING),
      new JsonFactory().createJsonParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  void 'test getText()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    skipUntil(jsonParser, JsonToken.FIELD_NAME)

    when:
    final result = jsonParser.getText()

    then:
    result == 'key1'
    1 * propagationModule.taintIfInputIsTainted('key1', jsonParser)
    0 * _

    where:
    jsonParser << [
      new JsonFactory().createJsonParser(JSON_STRING),
      new JsonFactory().createJsonParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  void 'test nextTextValue()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    skipUntil(jsonParser, JsonToken.FIELD_NAME)

    when:
    final result = jsonParser.nextTextValue()

    then:
    result == 'value1'
    1 * propagationModule.taintIfInputIsTainted('value1', jsonParser)
    0 * _

    where:
    jsonParser << [
      new JsonFactory().createJsonParser(JSON_STRING),
      new JsonFactory().createJsonParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  private void skipUntil(final JsonParser parser, final JsonToken token) {
    JsonToken current
    while ((current = parser.nextToken()) != null) {
      if (current == token) {
        break
      }
    }
  }
}
