import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.HttpMethod
import org.apache.commons.httpclient.methods.DeleteMethod
import org.apache.commons.httpclient.methods.GetMethod
import org.apache.commons.httpclient.methods.HeadMethod
import org.apache.commons.httpclient.methods.OptionsMethod
import org.apache.commons.httpclient.methods.PostMethod
import org.apache.commons.httpclient.methods.PutMethod
import spock.lang.Shared
import spock.lang.Timeout

abstract class CommonsHttpClientTest extends HttpClientTest {
  @Shared
  def client = new HttpClient()

  def setupSpec() {
    client.httpConnectionManager.params.connectionTimeout = CONNECT_TIMEOUT_MS
    client.httpConnectionManager.params.soTimeout = READ_TIMEOUT_MS
  }

  @Override
  CharSequence component() {
    return "commons-http-client"
  }

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def httpMethod = createHttpMethod(method, uri)
    headers.each { key, value ->
      httpMethod.setRequestHeader(key, value)
    }
    if (body && httpMethod instanceof PostMethod) {
      ((PostMethod) httpMethod).setRequestBody(body)
    } else if (body && httpMethod instanceof PutMethod) {
      ((PutMethod) httpMethod).setRequestBody(body)
    }

    client.executeMethod(httpMethod)
    callback?.call()

    def statusCode = httpMethod.getStatusCode()
    httpMethod.releaseConnection()
    return statusCode
  }

  @Override
  boolean testRedirects() {
    true
  }

  @Override
  boolean testRemoteConnection() {
    false
  }

  static HttpMethod createHttpMethod(String method, URI uri) {
    switch (method) {
      case "GET":
        return new GetMethod(uri.toString())
      case "POST":
        return new PostMethod(uri.toString())
      case "PUT":
        return new PutMethod(uri.toString())
      case "DELETE":
        return new DeleteMethod(uri.toString())
      case "HEAD":
        return new HeadMethod(uri.toString())
      case "OPTIONS":
        return new OptionsMethod(uri.toString())
      default:
        throw new IllegalArgumentException("Unsupported HTTP method: " + method)
    }
  }
}

@Timeout(5)
class CommonsHttpClientV0ForkedTest extends CommonsHttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {
}

@Timeout(5)
class CommonsHttpClientV1ForkedTest extends CommonsHttpClientTest implements TestingGenericHttpNamingConventions.ClientV1 {
}
