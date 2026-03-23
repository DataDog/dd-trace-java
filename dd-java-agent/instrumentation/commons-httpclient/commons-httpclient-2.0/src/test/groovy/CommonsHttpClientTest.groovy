import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.instrumentation.commonshttpclient.CommonsHttpClientDecorator
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.HttpMethod
import org.apache.commons.httpclient.methods.DeleteMethod
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.commons.httpclient.methods.HeadMethod
import org.apache.commons.httpclient.methods.OptionsMethod
import org.apache.commons.httpclient.methods.PostMethod
import org.apache.commons.httpclient.methods.PutMethod
import org.apache.commons.httpclient.methods.TraceMethod
import spock.lang.Shared
import spock.lang.Timeout

abstract class CommonsHttpClientTest extends HttpClientTest {

  @Shared
  HttpClient client = new HttpClient()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    HttpMethod httpMethod = createMethod(method, uri)

    try {
      headers.each { key, value ->
        httpMethod.setRequestHeader(key, value)
      }

      client.executeMethod(httpMethod)
      callback?.call()
      return httpMethod.getStatusCode()
    } finally {
      httpMethod.releaseConnection()
    }
  }

  HttpMethod createMethod(String method, URI uri) {
    def url = uri.toString()
    switch (method.toUpperCase()) {
      case "GET":
        return new GetMethod(url)
      case "POST":
        return new PostMethod(url)
      case "PUT":
        return new PutMethod(url)
      case "DELETE":
        return new DeleteMethod(url)
      case "HEAD":
        return new HeadMethod(url)
      case "OPTIONS":
        return new OptionsMethod(url)
      case "TRACE":
        return new TraceMethod(url)
      default:
        throw new IllegalArgumentException("Unsupported method: " + method)
    }
  }

  @Override
  String component() {
    return CommonsHttpClientDecorator.DECORATE.component()
  }

  @Override
  boolean testRedirects() {
    // Commons HttpClient 2.0 doesn't follow redirects by default
    return false
  }

  @Override
  boolean testCircularRedirects() {
    // Commons HttpClient 2.0 doesn't follow redirects by default
    return false
  }

  @Override
  boolean testConnectionFailure() {
    return false
  }

  @Override
  boolean testRemoteConnection() {
    // Commons HttpClient 2.0 may have issues with HTTPS in tests
    return false
  }
}

@Timeout(5)
class CommonsHttpClientV0ForkedTest extends CommonsHttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

@Timeout(5)
class CommonsHttpClientV1ForkedTest extends CommonsHttpClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}
