import com.fasterxml.jackson.core.JsonFactory
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

    when:
    jsonParser.currentName()

    then:
    1 * propagationModule.taintIfInputIsTainted( _ , jsonParser)
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

    when:
    jsonParser.getCurrentName()

    then:
    1 * propagationModule.taintIfInputIsTainted(_, jsonParser)
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

    when:
    jsonParser.nextToken()
    System.out.println(jsonParser)
    final result = jsonParser.getText()

    then:
    result == '{'
    1 * propagationModule.taintIfInputIsTainted('{', jsonParser)
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

    when:
    jsonParser.getValueAsString()

    then:
    1 * propagationModule.taintIfInputIsTainted(_, jsonParser)
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

    when:
    jsonParser.nextFieldName()

    then:
    1 * propagationModule.taintIfInputIsTainted(_, jsonParser)
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

    when:
    jsonParser.nextTextValue()

    then:
    1 * propagationModule.taintIfInputIsTainted(_, jsonParser)
    0 * _

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }
}
