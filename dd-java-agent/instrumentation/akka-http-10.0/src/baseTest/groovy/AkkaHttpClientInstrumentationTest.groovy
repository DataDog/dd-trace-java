import akka.actor.ActorSystem
import akka.http.Version
import akka.http.javadsl.ClientTransport
import akka.http.javadsl.ConnectionContext
import akka.http.javadsl.Http
import akka.http.javadsl.model.HttpMethods
import akka.http.javadsl.model.HttpRequest
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.model.headers.RawHeader
import akka.http.scaladsl.HttpsConnectionContext
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import datadog.trace.agent.test.base.HttpClientTest
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

  @Shared
  ClientTransport proxyTransport = ClientTransport.httpsProxy(InetSocketAddress.createUnresolved("localhost", proxy.port))

  @Shared
  ConnectionPoolSettings poolSettingsDefault = ConnectionPoolSettings.create(system)

  @Shared
  ConnectionPoolSettings poolSettingsWithHttpsProxy = poolSettingsDefault.withTransport(proxyTransport)

  @Shared
  ConnectionContext connectionContext = ConnectionContext.https(server.sslContext)


  abstract CompletionStage<HttpResponse> doRequest(HttpRequest request, ConnectionPoolSettings poolSettings)

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def isProxy = uri.fragment != null && uri.fragment.equals("proxy")
    def request = HttpRequest.create(uri.toString())
      .withMethod(HttpMethods.lookup(method).get())
      .addHeaders(headers.collect { RawHeader.create(it.key, it.value) })

    def response
    try {
      response = doRequest(request, isProxy ? poolSettingsWithHttpsProxy : poolSettingsDefault)
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
  String expectedOperationName() {
    return "akka-http.client.request"
  }

  @Override
  boolean testRedirects() {
    false
  }

  @Override
  boolean testProxy() {
    // Can't figure out why it's not using the proxy.
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

class AkkaHttpJavaClientInstrumentationTest extends AkkaHttpClientInstrumentationTest {
  @Override
  CompletionStage<HttpResponse> doRequest(HttpRequest request, ConnectionPoolSettings poolSettings) {
    if (callsNeedMaterializer) {
      return Http.get(system).singleRequest(request, connectionContext, poolSettings, system.log(), materializer)
    }
    return Http.get(system).singleRequest(request, connectionContext, poolSettings, system.log())
  }
}

class AkkaHttpScalaClientInstrumentationTest extends AkkaHttpClientInstrumentationTest {
  @Override
  CompletionStage<HttpResponse> doRequest(HttpRequest request, ConnectionPoolSettings poolSettings) {
    def http = akka.http.scaladsl.Http.apply(system)
    def sRequest = (akka.http.scaladsl.model.HttpRequest) request
    Future<akka.http.scaladsl.model.HttpResponse> f = null
    if (callsNeedMaterializer) {
      f = http.singleRequest(sRequest, connectionContext as HttpsConnectionContext, (ConnectionPoolSettings) poolSettings, system.log(), materializer)
    } else {
      f = http.singleRequest(sRequest, connectionContext as HttpsConnectionContext, (ConnectionPoolSettings) poolSettings, system.log())
    }
    return FutureConverters.toJava(f)
  }
}
