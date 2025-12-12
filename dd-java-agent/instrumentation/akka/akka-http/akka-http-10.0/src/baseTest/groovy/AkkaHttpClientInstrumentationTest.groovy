import akka.actor.ActorSystem
import akka.http.Version
import akka.http.javadsl.Http
import akka.http.javadsl.model.HttpMethods
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.model.headers.RawHeader
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.akkahttp.AkkaHttpClientDecorator
import scala.compat.java8.FutureConverters
import scala.concurrent.Future
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CompletionStage

@Timeout(5)
abstract class AkkaHttpClientInstrumentationTest extends HttpClientTest {
  @Shared
  ActorSystem system = ActorSystem.create()
  @Shared
  boolean callsNeedMaterializer = {
    ->
    def ver = Version.current()
    // Skip the materializer in the calls for 10.2+
    ver.startsWith("10.0.") || ver.startsWith("10.1.")
  }()
  @Shared
  ActorMaterializer materializer = callsNeedMaterializer ? ActorMaterializer.create(system) : null

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
    return AkkaHttpClientDecorator.DECORATE.component()
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
    if (callsNeedMaterializer) {
      Http.get(system).singleRequest(null, materializer)
    } else {
      Http.get(system).singleRequest(null)
    }

    then:
    def exception = thrown NullPointerException
    assertTraces(1) {
      trace(1) {
        span {
          parent()
          operationName operation()
          resourceName operation() // resource name is not set so defaults to operationName
          spanType DDSpanTypes.HTTP_CLIENT
          errored true
          tags {
            "$Tags.COMPONENT" "akka-http-client"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            errorTags(exception)
            defaultTags(false, false)
          }
        }
      }
    }
  }
}


abstract class AkkaHttpJavaClientInstrumentationTest extends AkkaHttpClientInstrumentationTest {
  @Override
  CompletionStage<HttpResponse> doRequest(HttpRequest request) {
    if (callsNeedMaterializer) {
      return Http.get(system).singleRequest(request, materializer)
    }
    return Http.get(system).singleRequest(request)
  }
}


abstract class AkkaHttpScalaClientInstrumentationTest extends AkkaHttpClientInstrumentationTest {
  @Override
  CompletionStage<HttpResponse> doRequest(HttpRequest request) {
    def http = akka.http.scaladsl.Http.apply(system)
    def sRequest = (akka.http.scaladsl.model.HttpRequest) request
    Future<akka.http.scaladsl.model.HttpResponse> f = null
    if (callsNeedMaterializer) {
      f = http.singleRequest(sRequest, http.defaultClientHttpsContext(), (ConnectionPoolSettings) ConnectionPoolSettings.apply(system), system.log(), materializer)
    } else {
      f = http.singleRequest(sRequest, http.defaultClientHttpsContext(), (ConnectionPoolSettings) ConnectionPoolSettings.apply(system), system.log())
    }
    return FutureConverters.toJava(f)
  }
}

class AkkaHttpJavaClientInstrumentationV0ForkedTest extends AkkaHttpJavaClientInstrumentationTest {

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
    return "akka-http.client.request"
  }
}

class AkkaHttpJavaClientInstrumentationV1ForkedTest extends AkkaHttpJavaClientInstrumentationTest implements TestingGenericHttpNamingConventions.ClientV1 {
}

class AkkaHttpScalaClientInstrumentationV0ForkedTest extends AkkaHttpScalaClientInstrumentationTest {

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
    return "akka-http.client.request"
  }
}

class AkkaHttpScalaClientInstrumentationV1ForkedTest extends AkkaHttpScalaClientInstrumentationTest implements TestingGenericHttpNamingConventions.ClientV1{
}
