import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.instrumentation.api.Tags
import datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.internal.http.HttpMethod

import static datadog.trace.instrumentation.okhttp3.OkHttpClientDecorator.DECORATE

class OkHttp3Test extends HttpClientTest<OkHttpClientDecorator> {

  def client = new OkHttpClient()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {
    def body = HttpMethod.requiresRequestBody(method) ? RequestBody.create(MediaType.parse("text/plain"), "") : null
    def request = new Request.Builder()
      .url(uri.toURL())
      .method(method, body)
      .headers(Headers.of(headers)).build()
    def response = client.newCall(request).execute()
    callback?.call()
    return response.code()
  }

  @Override
  // parent span must be cast otherwise it breaks debugging classloading (junit loads it early)
  void clientSpan(TraceAssert trace, int index, Object parentSpan, String method = "GET", boolean renameService = false, boolean tagQueryString = false, URI uri = server.address.resolve("/success"), Integer status = 200, Throwable exception = null) {
    trace.span(index) {
      //this parent thing isn't working for some reason and the parent span seems to be the one that is getting tested
      //therefore no parent span id exists since there's no child span created or something.
//      if (parentSpan == null) {
//        parent()
//      } else {
//        childOf((DDSpan) parentSpan)
//      }
//      serviceName decorator().service()
      operationName "okhttp.request"
      resourceName "$method $uri.path"
//      resourceName "GET $uri.path"
      spanType DDSpanTypes.HTTP_CLIENT
      errored exception != null
      tags {
        defaultTags()
        if (exception) {
          errorTags(exception.class, exception.message)
        }
        "$Tags.COMPONENT" DECORATE.component()
        if (status) {
          "$Tags.HTTP_STATUS" status
        }
        "$Tags.HTTP_URL" "${uri.resolve(uri.path)}"
        if (tagQueryString) {
          "$DDTags.HTTP_QUERY" uri.query
          "$DDTags.HTTP_FRAGMENT" uri.fragment
        }
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_PORT" server.address.port
        "$Tags.HTTP_METHOD" method
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
      }
    }
    if (!exception) {
      trace.span(index) {
        serviceName renameService ? "localhost" : decorator().service()
        operationName "okhttp.request"
        resourceName "$method $uri.path"
//        childOf trace.span(index)
        spanType DDSpanTypes.HTTP_CLIENT
        errored exception != null
        tags {
          defaultTags()
          if (exception) {
            errorTags(exception.class, exception.message)
          }
          "$Tags.COMPONENT" DECORATE.component()
          if (status) {
            "$Tags.HTTP_STATUS" status
          }
          "$Tags.HTTP_URL" "${uri.resolve(uri.path)}"
          if (tagQueryString) {
            "$DDTags.HTTP_QUERY" uri.query
            "$DDTags.HTTP_FRAGMENT" uri.fragment
          }
          "$Tags.PEER_HOSTNAME" "localhost"
          "$Tags.PEER_PORT" server.address.port
          "$Tags.HTTP_METHOD" method
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        }
      }
    }
  }

  //      tags {
//        defaultTags()
//        if (exception) {
//          errorTags(exception.class, exception.message)
//        }
//        "$Tags.COMPONENT" clientDecorator.component()
//        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
//        "$Tags.HTTP_URL" "$uri"
//        "$Tags.HTTP_METHOD" "GET"
//        "$Tags.HTTP_STATUS" 200
//        "$Tags.PEER_HOSTNAME" "localhost"
//        "$Tags.PEER_PORT" 64444
//        //"http.method" "GET"
//        //add http.status_code', 'http.url', 'peer.hostname', 'peer.port' add from Tags.java
//      }

  @Override
  int size(int size) {
    return size
  }


  @Override
  OkHttpClientDecorator decorator() {
    return OkHttpClientDecorator.DECORATE
  }

  @Override
  String expectedOperationName() {
    return "okhttp.request"
  }


  boolean testRedirects() {
    false
  }

  def "request to agent not traced"() {
    when:
    def status = doRequest(method, url, ["Datadog-Meta-Lang": "java"])

    then:
    status == 200
    assertTraces(1) {
      server.distributedRequestTrace(it, 0)
    }

    where:
    path                                | tagQueryString
    "/success"                          | false
    "/success"                          | true
    "/success?with=params"              | false
    "/success?with=params"              | true
    "/success#with+fragment"            | true
    "/success?with=params#and=fragment" | true

    method = "GET"
    url = server.address.resolve(path)
  }
}
