import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import groovy.transform.CompileDynamic

class Json2ParserInstrumentationTest extends AgentTestRunner {

  private final static String JSON_STRING = '{"key1":"value1","key2":"value2","key3":"value3"}'

  @CompileDynamic
  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test currentName()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    skipUntil(jsonParser, JsonToken.FIELD_NAME)

    when:
    final result = jsonParser.currentName()

    then:
    result == 'key1'
    1 * propagationModule.taintIfInputIsTainted('key1', jsonParser)
    0 * _

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
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
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
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
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  void 'test getValueAsString()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    skipUntil(jsonParser, JsonToken.VALUE_STRING)

    when:
    final result = jsonParser.getValueAsString()

    then:
    result == 'value1'
    1 * propagationModule.taintIfInputIsTainted('value1', jsonParser)
    0 * _

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  void 'test nextFieldName()'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    skipUntil(jsonParser, JsonToken.START_OBJECT)

    when:
    final result = jsonParser.nextFieldName()

    then:
    result == 'key1'
    1 * propagationModule.taintIfInputIsTainted('key1', jsonParser)
    0 * _

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
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
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
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
