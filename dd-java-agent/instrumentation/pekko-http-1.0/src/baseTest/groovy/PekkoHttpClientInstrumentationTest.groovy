import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.settings.ConnectionPoolSettings
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.pekkohttp.PekkoHttpClientDecorator
import scala.compat.java8.FutureConverters
import scala.concurrent.Future
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CompletionStage

@Timeout(5)
abstract class PekkoHttpClientInstrumentationTest extends HttpClientTest {
  @Shared
  ActorSystem system = ActorSystem.create()

  abstract CompletionStage<HttpResponse> doRequest(HttpRequest request)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def request = HttpRequest.create(uri.toString())
      .withMethod(HttpMethods.lookup(method).get())
      .addHeaders(headers.collect { RawHeader.create(it.key, it.value) })

    def response
    try {
      response = doRequest(request)
        .whenComplete { result, error ->
          callback?.call()
        }
        .toCompletableFuture()
        .get()
    } finally {
      // Since the spans are completed in an async callback, we need to wait here
      blockUntilChildSpansFinished(1)
    }
    return response.status().intValue()
  }

  @Override
  CharSequence component() {
    return PekkoHttpClientDecorator.DECORATE.component()
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
    Http.get(system).singleRequest(null)

    then:
    def exception = thrown NullPointerException
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName operation()
          resourceName "pekko-http.client.request"
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          tags {
            "$Tags.COMPONENT" "pekko-http-client"
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


abstract class PekkoHttpJavaClientInstrumentationTest extends PekkoHttpClientInstrumentationTest {
  @Override
  CompletionStage<HttpResponse> doRequest(HttpRequest request) {
    return Http.get(system).singleRequest(request)
  }
}


abstract class PekkoHttpScalaClientInstrumentationTest extends PekkoHttpClientInstrumentationTest {
  @Override
  CompletionStage<HttpResponse> doRequest(HttpRequest request) {
    def http = org.apache.pekko.http.scaladsl.Http.apply(system)
    def sRequest = (org.apache.pekko.http.scaladsl.model.HttpRequest) request
    Future<org.apache.pekko.http.scaladsl.model.HttpResponse> f = http.singleRequest(sRequest, http.defaultClientHttpsContext(), (ConnectionPoolSettings) ConnectionPoolSettings.apply(system), system.log())
    return FutureConverters.toJava(f)
  }
}

class PekkoHttpJavaClientInstrumentationV0ForkedTest extends PekkoHttpJavaClientInstrumentationTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "pekko-http.client.request"
  }
}

class PekkoHttpJavaClientInstrumentationV1ForkedTest extends PekkoHttpJavaClientInstrumentationTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

class PekkoHttpScalaClientInstrumentationV0ForkedTest extends PekkoHttpScalaClientInstrumentationTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String service() {
    return null
  }

  @Override
  String operation() {
    return "pekko-http.client.request"
  }
}

class PekkoHttpScalaClientInstrumentationV1ForkedTest extends PekkoHttpScalaClientInstrumentationTest implements TestingGenericHttpNamingConventions.ClientV1{

}
