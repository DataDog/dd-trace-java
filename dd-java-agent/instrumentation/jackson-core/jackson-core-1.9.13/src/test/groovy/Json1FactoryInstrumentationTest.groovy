import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.propagation.PropagationModule
import datadog.trace.api.iast.sink.SsrfModule
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.JsonParser
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class Json1FactoryInstrumentationTest extends InstrumentationSpecification {

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
    final result = new JsonFactory().createJsonParser(content)

    then:
    result != null
    1 * propagationModule.taintObjectIfTainted(_ as JsonParser, content)
    0 * _
  }

  void 'test createParser(InputStream)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final is = Mock(InputStream)

    when:
    final result = new JsonFactory().createJsonParser(is)

    then:
    result != null
    1 * propagationModule.taintObjectIfTainted(_ as JsonParser, is)
    2 * is.read(_, _, _)
    0 * _
  }

  void 'test createParser(Reader)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final reader = Mock(Reader)

    when:
    final result = new JsonFactory().createJsonParser(reader)


    then:
    result != null
    1 * propagationModule.taintObjectIfTainted(_ as JsonParser, reader)
    0 * _
  }

  void 'test createParser(URL)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    final ssrfModule = Mock(SsrfModule)
    [propagationModule, ssrfModule].each(InstrumentationBridge.&registerIastModule)
    final url = new URL("${clientServer.address}/json")

    when:
    final parser = new JsonFactory().createJsonParser(url)

    then:
    parser != null
    1 * propagationModule.taintObjectIfTainted(_ as JsonParser, url)
    1 * ssrfModule.onURLConnection(url)
    0 * _
  }


  void 'test createParser(byte[])'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final bytes = '{}'.bytes

    when:
    final result = new JsonFactory().createJsonParser(bytes)


    then:
    result != null
    1 * propagationModule.taintObjectIfTainted(_ as JsonParser, bytes)
    0 * _
  }

  void 'test createParser(byte[], int, int)'() {
    setup:
    final propagationModule = Mock(PropagationModule)
    InstrumentationBridge.registerIastModule(propagationModule)
    final bytes = '{}'.bytes

    when:
    final parser = new JsonFactory().createJsonParser(bytes, 0, 2)

    then:
    parser != null
    1 * propagationModule.taintObjectIfRangeTainted(_ as JsonParser, bytes, 0, 2, false, VulnerabilityMarks.NOT_MARKED)
    0 * _
  }
}
