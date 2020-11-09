import akka.actor.ActorSystem
import akka.http.javadsl.Http
import akka.http.javadsl.model.HttpMethods
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.akkahttp.AkkaHttpClientDecorator
import org.junit.Ignore
import spock.lang.Shared
import spock.lang.Timeout

@Ignore // Ignore this test until akka actor messaging is fixed
@Timeout(5)
class AkkaHttpClientInstrumentationTest extends HttpClientTest {

  @Shared
  ActorSystem system = ActorSystem.create()
  @Shared
  ActorMaterializer materializer = ActorMaterializer.create(system)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, Closure callback) {
    def request = HttpRequest.create(uri.toString())
      .withMethod(HttpMethods.lookup(method).get())
      .addHeaders(headers.collect { RawHeader.create(it.key, it.value) })

    def response
    try {
      response = Http.get(system)
        .singleRequest(request, materializer)
      //.whenComplete { result, error ->
      // FIXME: Callback should be here instead.
      //  callback?.call()
      //}
        .toCompletableFuture()
        .get()
    } finally {
      // FIXME: remove this when callback above works.
      blockUntilChildSpansFinished(1)
    }
    callback?.call()
    return response.status().intValue()
  }

  @Override
  CharSequence component() {
    return AkkaHttpClientDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "akka-http.client.request"
  }

  @Override
  boolean testRedirects() {
    false
  }

  @Override
  boolean testRemoteConnection() {
    // Not sure how to properly set timeouts...
    return false
  }

  def "singleRequest exception trace"() {
    when:
    // Passing null causes NPE in singleRequest
    Http.get(system).singleRequest(null, materializer)

    then:
    def exception = thrown NullPointerException
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName "akka-http.client.request"
          resourceName "akka-http.client.request"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          tags {
            "$Tags.COMPONENT" "akka-http-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            errorTags(exception)
            defaultTags()
          }
        }
      }
    }

    where:
    renameService << [false, true]
  }
}
