import com.fasterxml.jackson.core.JsonFactory
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.JacksonModule
import foo.bar.JsonParserTestSuite

class JsonParserInstrumentationTest extends AgentTestRunner {

  private final static String JSON_STRING = '{"key1":"value1","key2":"value2","key3":"value3"}'

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  def 'test currentName()'() {
    setup:
    final jacksonModule = Mock(JacksonModule)
    InstrumentationBridge.registerIastModule(jacksonModule)
    final jsonParserTestSuite = new JsonParserTestSuite(jsonParser)

    when:
    jsonParserTestSuite.currentName()

    then:
    1 * jacksonModule.onJsonParserGetString(jsonParser, _)

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  //abstract
  def 'test getCurrentName()'() {
    setup:
    final jacksonModule = Mock(JacksonModule)
    InstrumentationBridge.registerIastModule(jacksonModule)
    final jsonParserTestSuite = new JsonParserTestSuite(jsonParser)

    when:
    jsonParserTestSuite.getCurrentName()

    then:
    1 * jacksonModule.onJsonParserGetString(jsonParser, null)

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  //abstract
  def 'test getText()'() {
    setup:
    final jacksonModule = Mock(JacksonModule)
    InstrumentationBridge.registerIastModule(jacksonModule)
    final jsonParserTestSuite = new JsonParserTestSuite(jsonParser)

    when:
    final result = jsonParserTestSuite.getText()

    then:
    result == '{'
    1 * jacksonModule.onJsonParserGetString(jsonParser, '{')

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }



  def 'test getValueAsString()'() {
    setup:
    final jacksonModule = Mock(JacksonModule)
    InstrumentationBridge.registerIastModule(jacksonModule)
    final jsonParserTestSuite = new JsonParserTestSuite(jsonParser)

    when:
    final result = jsonParserTestSuite.getValueAsString()

    then:
    result == 'key1'
    1 * jacksonModule.onJsonParserGetString(jsonParser, 'key1')

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }

  /*
   //abstract
   def 'test getValueAsString(String)'() {
   setup:
   final jacksonModule = Mock(JacksonModule)
   InstrumentationBridge.registerIastModule(jacksonModule)
   final jsonParserTestSuite = new JsonParserTestSuite(jsonParser)
   when:
   final result = jsonParserTestSuite.getValueAsString("test")
   then:
   result == 'key1'
   1 * jacksonModule.onJsonParserGetString(jsonParser, 'key1')
   where:
   jsonParser << [new JsonFactory().createParser(JSON_STRING), new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))]
   }
   */

  def 'test nextFieldName()'() {
    setup:
    final jacksonModule = Mock(JacksonModule)
    InstrumentationBridge.registerIastModule(jacksonModule)
    final jsonParserTestSuite = new JsonParserTestSuite(jsonParser)

    when:
    final result = jsonParserTestSuite.nextFieldName()

    then:
    result == 'key1'
    1 * jacksonModule.onJsonParserGetString(jsonParser, 'key1')

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }


  def 'test nextTextValue()'() {
    setup:
    final jacksonModule = Mock(JacksonModule)
    InstrumentationBridge.registerIastModule(jacksonModule)
    final jsonParserTestSuite = new JsonParserTestSuite(jsonParser)

    when:
    jsonParserTestSuite.nextTextValue()

    then:
    1 * jacksonModule.onJsonParserGetString(jsonParser, null)

    where:
    jsonParser << [
      new JsonFactory().createParser(JSON_STRING),
      new JsonFactory().createParser(new ByteArrayInputStream(JSON_STRING.getBytes()))
    ]
  }
}
