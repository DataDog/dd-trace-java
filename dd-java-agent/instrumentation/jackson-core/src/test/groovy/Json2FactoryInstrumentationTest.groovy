import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.SsrfModule
import groovy.transform.CompileDynamic
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class Json2FactoryInstrumentationTest extends AgentTestRunner {

  @Shared
  @AutoCleanup
  TestHttpServer clientServer = httpServer {
    handlers {
      prefix("/json") {
        final json = '{"key":"value"}'
        response.addHeader('Content-Type', 'application/json')
        response.status(200).send(json)
      }
    }
  }

  @CompileDynamic
  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.iast.enabled", "true")
  }

  void 'test createParser(String)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final content = '{"key":"value"}'

    when:
    final result = new JsonFactory().createParser(content)

    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, content)
    0 * _
  }

  void 'test createParser(InputStream)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final is = Mock(InputStream)

    when:
    final result = new JsonFactory().createParser(is)


    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, is)
    2 * is.read(_, _, _)
    0 * _
  }

  void 'test createParser(Reader)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final reader = Mock(Reader)

    when:
    final result = new JsonFactory().createParser(reader)


    then:
    result != null
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, reader)
    0 * _
  }

  void 'test createParser(URL)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    final ssrfModule = Mock(SsrfModule)
    [propagationModule, ssrfModule].each(InstrumentationBridge.&registerIastModule)
    final url = new URL("${clientServer.address}/json")

    when:
    final parser = new JsonFactory().createParser(url)
    parser.setCodec(new ObjectMapper())
    final json = parser.readValueAs(Map)

    then:
    parser != null
    json == [key: 'value']
    1 * propagationModule.taintIfInputIsTainted(_ as JsonParser, url)
    1 * propagationModule.taintIfInputIsTainted('key', _ as JsonParser)
    1 * propagationModule.taintIfInputIsTainted('value', _ as JsonParser)
    1 * ssrfModule.onURLConnection(url)
    0 * _
  }
}
