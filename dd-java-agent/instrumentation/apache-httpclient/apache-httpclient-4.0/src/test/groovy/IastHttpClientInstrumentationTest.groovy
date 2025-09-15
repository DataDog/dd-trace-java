import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.server.http.TestHttpServer
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.SsrfModule
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.BasicResponseHandler
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.message.BasicHttpRequest
import org.apache.http.protocol.BasicHttpContext
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class IastHttpClientInstrumentationTest extends InstrumentationSpecification {

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

  void 'test ssrf httpClient execute method expecting call module #iterationIndex'() {
    given:
    final ssrf = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(ssrf)
    final httpClient = new DefaultHttpClient()

    when:
    httpClient.execute(*args)

    then:
    1 * ssrf.onURLConnection(_ as URI)

    where:
    args << [
      [getHttpUriRequest(server)],
      [getHttpUriRequest(server), new BasicHttpContext()],
      [getHttpUriRequest(server), new BasicResponseHandler()],
      [getHttpUriRequest(server), new BasicResponseHandler(), new BasicHttpContext()],
      [getHttpHost(server), getHttpUriRequest(server)],
      [getHttpHost(server), getHttpUriRequest(server), new BasicHttpContext()],
      [getHttpHost(server), getHttpUriRequest(server), new BasicResponseHandler()],
      [getHttpHost(server), getHttpUriRequest(server), new BasicResponseHandler(), new BasicHttpContext()],
      [getHttpHost(server), getHttpRequest(server)],
      [getHttpHost(server), getHttpRequest(server), new BasicHttpContext()],
      [getHttpHost(server), getHttpRequest(server), new BasicResponseHandler()],
      [getHttpHost(server), getHttpRequest(server), new BasicResponseHandler(), new BasicHttpContext()]
    ]
  }

  private static org.apache.http.client.methods.HttpUriRequest getHttpUriRequest(final server){
    return new HttpGet(server.address.toString())
  }

  private static HttpRequest getHttpRequest(final server){
    return new BasicHttpRequest("GET", server.address.toString())
  }

  private static HttpHost getHttpHost(final TestHttpServer server){
    return new HttpHost(server.address.host, server.address.port, server.address.scheme)
  }
}
