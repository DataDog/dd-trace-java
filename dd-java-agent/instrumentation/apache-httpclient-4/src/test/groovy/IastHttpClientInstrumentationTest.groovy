import datadog.trace.agent.test.AgentTestRunner
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

  void 'test ssrf httpClient execute method with args #args expecting call module with #classExpected arg'() {
    given:
    final ssrf = Mock(SsrfModule)
    InstrumentationBridge.registerIastModule(ssrf)
    final httpClient = new DefaultHttpClient()

    when:
    httpClient.execute(*args)

    then:
    if(classExpected == String) {
      1 * ssrf.onURLConnection(_ as String)
    }

    where:
    args | classExpected
    [getHttpUriRequest(server)] | String
    [getHttpUriRequest(server), new BasicHttpContext()] | String
    [getHttpUriRequest(server), new BasicResponseHandler()] | String
    [getHttpUriRequest(server), new BasicResponseHandler(), new BasicHttpContext()] | String
    [getHttpHost(server), getHttpUriRequest(server)] | String
    [getHttpHost(server), getHttpUriRequest(server), new BasicHttpContext()] | String
    [getHttpHost(server), getHttpUriRequest(server), new BasicResponseHandler()] | String
    [getHttpHost(server), getHttpUriRequest(server), new BasicResponseHandler(), new BasicHttpContext()] | String
    [getHttpHost(server), getHttpRequest(server)] | String
    [getHttpHost(server), getHttpRequest(server), new BasicHttpContext()] | String
    [getHttpHost(server), getHttpRequest(server), new BasicResponseHandler()] | String
    [getHttpHost(server), getHttpRequest(server), new BasicResponseHandler(), new BasicHttpContext()] | String
  }

  private static org.apache.http.client.methods.HttpUriRequest getHttpUriRequest(final server){
    return new HttpGet(server.address.toString())
  }

  private static HttpRequest getHttpRequest(final server){
    return new BasicHttpRequest("GET", server.address.toString())
  }

  private static HttpHost getHttpHost(final server){
    return new HttpHost(server.address.host, server.address.port, server.address.scheme)
  }
}
