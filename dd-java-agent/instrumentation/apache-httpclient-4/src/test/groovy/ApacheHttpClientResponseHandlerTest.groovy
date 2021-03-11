import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.apachehttpclient.ApacheHttpClientDecorator
import org.apache.http.HttpHost
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.ResponseHandler
import org.apache.http.conn.params.ConnRoutePNames
import org.apache.http.conn.scheme.PlainSocketFactory
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.scheme.SchemeRegistry
import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.SingleClientConnManager
import org.apache.http.message.BasicHeader
import org.apache.http.params.HttpConnectionParams
import spock.lang.Shared
import spock.lang.Timeout

@Timeout(5)
class ApacheHttpClientResponseHandlerTest extends HttpClientTest {
  @Shared
  HttpClient directClient
  @Shared
  HttpClient proxiedClient

  @Shared
  def handler = new ResponseHandler<Integer>() {
    @Override
    Integer handleResponse(HttpResponse response) {
      return response.statusLine.statusCode
    }
  }

  def setupSpec() {
    def socketFactory = new SSLSocketFactory(server.sslContext)
    socketFactory.hostnameVerifier = SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER
    SchemeRegistry schemeRegistry = new SchemeRegistry()
    schemeRegistry.register(new Scheme("https", socketFactory, 443))
    schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80))

    directClient = new DefaultHttpClient(new SingleClientConnManager(null, schemeRegistry), null)
    HttpConnectionParams.setConnectionTimeout(directClient.getParams(), CONNECT_TIMEOUT_MS)
    HttpConnectionParams.setSoTimeout(directClient.getParams(), READ_TIMEOUT_MS)

    proxiedClient = new DefaultHttpClient(new SingleClientConnManager(null, schemeRegistry), null)
    HttpConnectionParams.setConnectionTimeout(proxiedClient.getParams(), CONNECT_TIMEOUT_MS)
    HttpConnectionParams.setSoTimeout(proxiedClient.getParams(), READ_TIMEOUT_MS)
    proxiedClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, new HttpHost("localhost", proxy.port))
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = new HttpUriRequest(method, uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def status = (isProxy ? proxiedClient : directClient).execute(request, handler)

    // handler execution is included within the client span, so we can't call the callback there.
    callback?.call()

    return status
  }

  @Override
  CharSequence component() {
    return ApacheHttpClientDecorator.DECORATE.component()
  }
}
