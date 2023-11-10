import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.SsrfModule
import org.apache.hc.client5.http.classic.HttpClient
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.impl.classic.BasicHttpClientResponseHandler
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.protocol.BasicHttpContext
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class IastHttpClientInstrumentationTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig('dd.iast.enabled', 'true')
  }

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix('/') {
        String msg = "Hello."
        response.status(200).send(msg)
      }
    }
  }

  void 'test'() {
    given:
    final ssrf = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(ssrf)

    when:
    suite.client.execute(*suite.args as Object[])

    then:
    1 * ssrf.onURLConnection(_)

    where:
    suite << createTestSuite()
  }


  private Iterable<TestSuite> createTestSuite() {
    final result = []
    result.addAll(createTestsForClient(HttpClients.createDefault()))
    result.addAll(createTestsForClient(HttpClients.createMinimal()))
    return result as Iterable<TestSuite>
  }

  private Iterable<TestSuite> createTestsForClient(HttpClient client){
    final result = []
    result.add(createTestSuite(client, [getHttpHost(), getClassicHttpRequest(), new BasicHttpContext()]))
    result.add(createTestSuite(client, [getClassicHttpRequest(), new BasicHttpContext()]))
    result.add(createTestSuite(client, [getClassicHttpRequest()]))
    result.add(createTestSuite(client, [getHttpHost(), getClassicHttpRequest()]))
    result.add(createTestSuite(client, [getClassicHttpRequest(), new BasicHttpClientResponseHandler()]))
    result.add(createTestSuite(client, [getClassicHttpRequest(), new BasicHttpContext(), new BasicHttpClientResponseHandler()]))
    result.add(createTestSuite(client, [getHttpHost(), getClassicHttpRequest(), new BasicHttpClientResponseHandler()]))
    result.add(createTestSuite(client, [getHttpHost(), getClassicHttpRequest(), new BasicHttpContext(), new BasicHttpClientResponseHandler()]))
    return result as Iterable<TestSuite>
  }

  private TestSuite createTestSuite(client, args) {
    return new TestSuite(
      description: "ssrf.onURLConnection is called for ${client} with ${args}",
      args: args,
      client: client,
    )
  }

  private static class TestSuite {
    String description
    HttpClient client
    String args

    @Override
    String toString() {
      return "IAST apache httpclient 5 test suite: ${description}"
    }
  }

  private  ClassicHttpRequest getClassicHttpRequest(){
    return new HttpGet(server.address.toString())
  }

  private  HttpHost getHttpHost(){
    return new HttpHost(server.address.scheme, server.address.host, server.address.port)
  }

}
