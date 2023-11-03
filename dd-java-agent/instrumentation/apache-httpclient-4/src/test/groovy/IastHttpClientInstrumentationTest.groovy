

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.SsrfModule
import org.apache.http.HttpHost
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.BasicResponseHandler
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.protocol.BasicHttpContext
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

  void 'test ssrf'() {
    given:
    final ssrf = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(ssrf)

    when:
    httpClient.execute(*args)

    then:
    1 * ssrf.onURLConnection(_ as HttpHost)

    where:
    httpClient | args
    new DefaultHttpClient() | [getHttpUriRequest(server)]
    new DefaultHttpClient() | [getHttpUriRequest(server), new BasicHttpContext()]
    new DefaultHttpClient() | [getHttpHost(server), getHttpUriRequest(server)]
    new DefaultHttpClient() | [getHttpHost(server), getHttpUriRequest(server), new BasicHttpContext()]
    new DefaultHttpClient() | [getHttpUriRequest(server), new BasicResponseHandler()]
    new DefaultHttpClient() | [getHttpUriRequest(server), new BasicResponseHandler(), new BasicHttpContext()]
    new DefaultHttpClient() | [getHttpHost(server), getHttpUriRequest(server), new BasicResponseHandler()]
    new DefaultHttpClient() | [getHttpHost(server), getHttpUriRequest(server), new BasicResponseHandler(), new BasicHttpContext()]
  }

  private static org.apache.http.client.methods.HttpUriRequest getHttpUriRequest(final server){
    return new HttpGet(server.address.toString())
  }

  private static HttpHost getHttpHost(final server){
    return new HttpHost(server.address.host, server.address.port, server.address.scheme)
  }
}
