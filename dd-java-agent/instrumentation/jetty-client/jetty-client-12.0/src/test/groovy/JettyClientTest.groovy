import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.HttpProxy
import org.eclipse.jetty.client.HttpResponseException
import org.eclipse.jetty.client.Request
import org.eclipse.jetty.client.Response
import org.eclipse.jetty.client.Result
import org.eclipse.jetty.client.StringRequestContent
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic
import org.eclipse.jetty.io.ClientConnector
import org.eclipse.jetty.util.ssl.SslContextFactory
import spock.lang.Shared
import spock.lang.Subject

import java.util.concurrent.ExecutionException

abstract class JettyClientTest extends HttpClientTest {

  @Shared
  @Subject
  HttpClient client = createHttpClient()

  @Shared
  HttpClient proxiedClient = createHttpClient()

  def createHttpClient() {
    SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true)
    ClientConnector clientConnector = new ClientConnector()
    clientConnector.setSslContextFactory(sslContextFactory)
    return new HttpClient(new HttpClientTransportDynamic(clientConnector))
  }

  def setupSpec() {
    client.connectTimeout = CONNECT_TIMEOUT_MS
    client.addressResolutionTimeout = CONNECT_TIMEOUT_MS
    client.idleTimeout = READ_TIMEOUT_MS
    client.start()

    proxiedClient.proxyConfiguration.addProxy (new HttpProxy("localhost", proxy.port))
    proxiedClient.connectTimeout = CONNECT_TIMEOUT_MS
    proxiedClient.addressResolutionTimeout = CONNECT_TIMEOUT_MS
    proxiedClient.idleTimeout = READ_TIMEOUT_MS
    proxiedClient.start()
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def proxy = uri.fragment != null && uri.fragment.equals("proxy")
    Request req = (proxy ? proxiedClient : client).newRequest(uri).method(method)
    headers.entrySet().each { h ->
      req.headers {it.add(h.key, h.value)}
    }
    if (body) {
      req.body(new StringRequestContent(body))
    }

    if (callback) {
      req.onComplete (new Response.CompleteListener() {
          @Override
          void onComplete(Result result) {
            callback.call()
          }
        })
    }
    try {
      def resp = req.send()
      blockUntilChildSpansFinished(1)
      return resp.status
    } catch (ExecutionException ex) {
      if (ex.cause instanceof HttpResponseException) {
        return (ex.cause as HttpResponseException).response.status
      }
      throw ex
    }
  }

  @Override
  CharSequence component() {
    return "jetty-client"
  }

  @Override
  boolean testRedirects() {
    false
  }

  @Override
  boolean testRemoteConnection() {
    false
  }

  @Override
  boolean testSecure() {
    true
  }

  @Override
  boolean testProxy() {
    false // doesn't produce CONNECT span.
  }
}

class JettyClientV0Test extends JettyClientTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

class JettyClientV1ForkedTest extends JettyClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}
