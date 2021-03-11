import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.instrumentation.apachehttpclient.ApacheHttpClientDecorator
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.conn.params.ConnRoutePNames
import org.apache.http.conn.scheme.PlainSocketFactory
import org.apache.http.conn.scheme.Scheme
import org.apache.http.conn.scheme.SchemeRegistry
import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.SingleClientConnManager
import org.apache.http.message.BasicHeader
import org.apache.http.message.BasicHttpRequest
import org.apache.http.params.HttpConnectionParams
import org.apache.http.protocol.BasicHttpContext
import spock.lang.Shared
import spock.lang.Timeout

abstract class ApacheHttpClientTest<T extends HttpRequest> extends HttpClientTest {
  @Shared
  HttpClient directClient
  @Shared
  HttpClient proxiedClient

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
  CharSequence component() {
    return ApacheHttpClientDecorator.DECORATE.component()
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = createRequest(method, uri)
    headers.entrySet().each {
      request.addHeader(new BasicHeader(it.key, it.value))
    }

    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def response = executeRequest(isProxy ? proxiedClient : directClient, request, uri)
    callback?.call()
    response.entity?.content?.close() // Make sure the connection is closed.

    return response.statusLine.statusCode
  }

  abstract T createRequest(String method, URI uri)

  abstract HttpResponse executeRequest(HttpClient client, T request, URI uri)

  static String fullPathFromURI(URI uri) {
    StringBuilder builder = new StringBuilder()
    if (uri.getPath() != null) {
      builder.append(uri.getPath())
    }

    if (uri.getQuery() != null) {
      builder.append('?')
      builder.append(uri.getQuery())
    }

    if (uri.getFragment() != null) {
      builder.append('#')
      builder.append(uri.getFragment())
    }
    return builder.toString()
  }
}

@Timeout(5)
class ApacheClientHostRequest extends ApacheHttpClientTest<BasicHttpRequest> {
  @Override
  BasicHttpRequest createRequest(String method, URI uri) {
    return new BasicHttpRequest(method, fullPathFromURI(uri))
  }

  @Override
  HttpResponse executeRequest(HttpClient client, BasicHttpRequest request, URI uri) {
    return client.execute(new HttpHost(uri.getHost(), uri.getPort()), request)
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  @Override
  boolean testSecure() {
    return false // org.apache.http.NoHttpResponseException: The target server failed to respond
  }

  @Override
  boolean testProxy() {
    return false // doesn't get proxied correctly
  }
}

@Timeout(5)
class ApacheClientHostRequestContext extends ApacheHttpClientTest<BasicHttpRequest> {
  @Override
  BasicHttpRequest createRequest(String method, URI uri) {
    return new BasicHttpRequest(method, fullPathFromURI(uri))
  }

  @Override
  HttpResponse executeRequest(HttpClient client, BasicHttpRequest request, URI uri) {
    return client.execute(new HttpHost(uri.getHost(), uri.getPort()), request, new BasicHttpContext())
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  @Override
  boolean testSecure() {
    return false // org.apache.http.NoHttpResponseException: The target server failed to respond
  }

  @Override
  boolean testProxy() {
    return false // doesn't get proxied correctly
  }
}

@Timeout(5)
class ApacheClientHostRequestResponseHandler extends ApacheHttpClientTest<BasicHttpRequest> {
  @Override
  BasicHttpRequest createRequest(String method, URI uri) {
    return new BasicHttpRequest(method, fullPathFromURI(uri))
  }

  @Override
  HttpResponse executeRequest(HttpClient client, BasicHttpRequest request, URI uri) {
    return client.execute(new HttpHost(uri.getHost(), uri.getPort()), request, { response -> response })
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  @Override
  boolean testSecure() {
    return false // org.apache.http.NoHttpResponseException: The target server failed to respond
  }

  @Override
  boolean testProxy() {
    return false // doesn't get proxied correctly
  }
}

@Timeout(5)
class ApacheClientHostRequestResponseHandlerContext extends ApacheHttpClientTest<BasicHttpRequest> {
  @Override
  BasicHttpRequest createRequest(String method, URI uri) {
    return new BasicHttpRequest(method, fullPathFromURI(uri))
  }

  @Override
  HttpResponse executeRequest(HttpClient client, BasicHttpRequest request, URI uri) {
    return client.execute(new HttpHost(uri.getHost(), uri.getPort()), request, { response -> response }, new BasicHttpContext())
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  @Override
  boolean testSecure() {
    return false // org.apache.http.NoHttpResponseException: The target server failed to respond
  }

  @Override
  boolean testProxy() {
    return false // doesn't get proxied correctly
  }
}

@Timeout(5)
class ApacheClientUriRequest extends ApacheHttpClientTest<HttpUriRequest> {
  @Override
  HttpUriRequest createRequest(String method, URI uri) {
    return new HttpUriRequest(method, uri)
  }

  @Override
  HttpResponse executeRequest(HttpClient client, HttpUriRequest request, URI uri) {
    return client.execute(request)
  }
}

@Timeout(5)
class ApacheClientUriRequestContext extends ApacheHttpClientTest<HttpUriRequest> {
  @Override
  HttpUriRequest createRequest(String method, URI uri) {
    return new HttpUriRequest(method, uri)
  }

  @Override
  HttpResponse executeRequest(HttpClient client, HttpUriRequest request, URI uri) {
    return client.execute(request, new BasicHttpContext())
  }
}

@Timeout(5)
class ApacheClientUriRequestResponseHandler extends ApacheHttpClientTest<HttpUriRequest> {
  @Override
  HttpUriRequest createRequest(String method, URI uri) {
    return new HttpUriRequest(method, uri)
  }

  @Override
  HttpResponse executeRequest(HttpClient client, HttpUriRequest request, URI uri) {
    return client.execute(request, { response -> response })
  }
}

@Timeout(5)
class ApacheClientUriRequestResponseHandlerContext extends ApacheHttpClientTest<HttpUriRequest> {
  @Override
  HttpUriRequest createRequest(String method, URI uri) {
    return new HttpUriRequest(method, uri)
  }

  @Override
  HttpResponse executeRequest(HttpClient client, HttpUriRequest request, URI uri) {
    return client.execute(request, { response -> response })
  }
}
