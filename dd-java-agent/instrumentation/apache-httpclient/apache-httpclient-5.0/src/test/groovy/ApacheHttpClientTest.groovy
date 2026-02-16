import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.apachehttpclient5.ApacheHttpClientDecorator
import java.util.concurrent.TimeUnit
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager
import org.apache.hc.core5.http.ClassicHttpRequest
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.HttpRequest
import org.apache.hc.core5.http.message.BasicClassicHttpRequest
import org.apache.hc.core5.http.message.BasicHeader
import org.apache.hc.core5.http.protocol.BasicHttpContext
import spock.lang.Shared
import spock.lang.Timeout

abstract class ApacheHttpClientTest<T extends HttpRequest> extends HttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {

  @Shared
  def client = HttpClients.custom()
  .setConnectionManager(new BasicHttpClientConnectionManager())
  .setDefaultRequestConfig(RequestConfig.custom()
  .setConnectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
  .build()).build()

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

    CloseableHttpResponse response = null
    try {
      response = executeRequest(request, uri)
      callback?.call()
      return response.code
    }
    finally {
      response?.close()
    }
  }

  abstract T createRequest(String method, URI uri)

  abstract CloseableHttpResponse executeRequest(T request, URI uri)

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
class ApacheClientHostRequest extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, fullPathFromURI(uri))
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(new HttpHost(uri.scheme, uri.host, uri.port), request)
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }

  def "test nested calls in interceptors are traced"() {
    given:
    def uri = new URI("http://localhost:${server.address.port}/success")
    def testClient = HttpClients.custom()
      .setConnectionManager(new BasicHttpClientConnectionManager())
      .setDefaultRequestConfig(RequestConfig.custom()
      .setConnectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      .build())
      .addRequestInterceptorLast {request, details, context ->
        doRequest("GET", uri)
      }.build()
    when:
    def response = testClient.execute(new HttpHost(uri.scheme, uri.host, uri.port), createRequest("GET", uri))

    then:
    assert response.getCode() == 200
    assertTraces(3) {
      trace(size(2)) {
        clientSpan(it, null)
        clientSpan(it, span(0))
      }
      server.distributedRequestTrace(it, trace(0).last())
      server.distributedRequestTrace(it, trace(0).first())
    }
    cleanup:
    response?.close()
  }
}

@Timeout(5)
class ApacheClientHostRequestContext extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, fullPathFromURI(uri))
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(new HttpHost(uri.scheme, uri.host, uri.port), request, new BasicHttpContext())
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }
}

@Timeout(5)
class ApacheClientHostRequestResponseHandler extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, fullPathFromURI(uri))
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(new HttpHost(uri.scheme, uri.host, uri.port), request, { CloseableHttpResponse response -> response })
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }
}

@Timeout(5)
class ApacheClientRequest extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, uri)
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(request)
  }
}

@Timeout(5)
class ApacheClientNullHostRequestContext extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, uri)
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(null, request, new BasicHttpContext())
  }
}

@Timeout(5)
class ApacheClientRequestResponseHandler extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, uri)
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(request, { CloseableHttpResponse response -> response })
  }
}

@Timeout(5)
class ApacheClientRequestContext extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, uri)
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(request, new BasicHttpContext())
  }
}

@Timeout(5)
class ApacheClientRequestContextResponseHandler extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, uri)
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(request, new BasicHttpContext(), { CloseableHttpResponse response -> response })
  }

  @Override
  boolean testRemoteConnection() {
    return false
  }
}

abstract class ApacheClientResponseHandlerAll extends ApacheHttpClientTest<ClassicHttpRequest> {
  @Override
  ClassicHttpRequest createRequest(String method, URI uri) {
    return new BasicClassicHttpRequest(method, fullPathFromURI(uri))
  }

  @Override
  CloseableHttpResponse executeRequest(ClassicHttpRequest request, URI uri) {
    return client.execute(new HttpHost(uri.scheme, uri.host, uri.port), request, new BasicHttpContext(), { CloseableHttpResponse response -> response })
  }
}

@Timeout(5)
class ApacheClientResponseHandlerAllV0Test extends ApacheClientResponseHandlerAll {
}

@Timeout(5)
class ApacheClientResponseHandlerAllV1ForkedTest extends ApacheClientResponseHandlerAll implements TestingGenericHttpNamingConventions.ClientV1 {
}
