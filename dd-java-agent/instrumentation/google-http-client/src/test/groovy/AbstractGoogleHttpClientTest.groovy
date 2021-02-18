import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequest
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.javanet.NetHttpTransport
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.googlehttpclient.GoogleHttpClientDecorator
import spock.lang.Shared

abstract class AbstractGoogleHttpClientTest extends HttpClientTest {

  @Shared
  def requestFactory = new NetHttpTransport().createRequestFactory()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    doRequest(method, uri, headers, callback, false)
  }

  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback, boolean throwExceptionOnError) {
    GenericUrl genericUrl = new GenericUrl(uri)

    HttpRequest request = requestFactory.buildRequest(method, genericUrl, null)
    request.connectTimeout = CONNECT_TIMEOUT_MS
    request.readTimeout = READ_TIMEOUT_MS

    // GenericData::putAll method converts all known http headers to List<String>
    // and lowercase all other headers
    def ci = request.getHeaders().getClassInfo()
    request.getHeaders().putAll(headers.collectEntries { name, value
      -> [(name): (ci.getFieldInfo(name) != null ? [value] : value.toLowerCase())]
    })

    request.setThrowExceptionOnExecuteError(throwExceptionOnError)

    HttpResponse response = executeRequest(request)
    callback?.call()

    return response.getStatusCode()
  }

  abstract HttpResponse executeRequest(HttpRequest request)

  @Override
  String component() {
    return GoogleHttpClientDecorator.DECORATE.component()
  }

  @Override
  boolean testCircularRedirects() {
    // Circular redirects don't throw an exception with Google Http Client
    return false
  }

  def "error traces when exception is not thrown"() {
    given:
    def uri = server.address.resolve("/error")

    when:
    def status = doRequest(method, uri)

    then:
    status == 500
    assertTraces(2) {
      trace(size(1)) {
        span {
          resourceName "$method $uri.path"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          tags {
            "$Tags.COMPONENT" "google-http-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" Integer
            "$Tags.HTTP_URL" String
            "$Tags.HTTP_METHOD" String
            "$Tags.HTTP_STATUS" Integer
            "$DDTags.ERROR_MSG" "Server Error"
            defaultTags()
          }
        }
      }
      server.distributedRequestTrace(it, trace(0).last())
    }

    where:
    method = "GET"
  }
}
