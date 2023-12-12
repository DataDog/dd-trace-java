package dd.trace.instrumentation.springwebflux6.client

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpClientTest
import datadog.trace.agent.test.naming.TestingGenericHttpNamingConventions
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.naming.SpanNaming
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.URIUtils
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator
import datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

abstract class SpringWebfluxHttpClientBase extends HttpClientTest implements TestingGenericHttpNamingConventions.ClientV0 {

  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  abstract WebClient createClient(CharSequence component)

  abstract void check()

  @Override
  int doRequest(String method, URI uri, Map<String, String> headers, String body, Closure callback) {
    def hasParent = activeSpan() != null
    def client = createClient(component())
    ClientResponse response = client.method(HttpMethod.valueOf(method))
    .uri(uri)
    .headers {
      h -> headers.forEach({
        key, value -> h.add(key, value)
      })
    }
    .exchangeToMono (Mono::just)
    .doFinally {
      it -> callback?.call()
    }
    .block()

    if (hasParent) {
      blockUntilChildSpansFinished(callback ? 3 : 2)
    }

    check()

    response.statusCode().value()
  }

  @Override
  CharSequence component() {
    return SpringWebfluxHttpClientDecorator.DECORATE.component()
  }


  @Override
  // parent spanRef must be cast otherwise it breaks debugging classloading (junit loads it early)
  void clientSpan(
  TraceAssert trace,
  Object parentSpan,
  String method = "GET",
  boolean renameService = false,
  boolean tagQueryString = false,
  URI uri = server.address.resolve("/success"),
  Integer status = 200,
  boolean error = false,
  Throwable exception = null,
  boolean ignorePeer = false,
  Map<String, Serializable> extraTags = null) {
    def leafParentId = trace.spanAssertCount.get()
    super.clientSpan(trace, parentSpan, method, renameService, tagQueryString, uri, status, error, exception, ignorePeer, extraTags)
    if (!exception) {
      def expectedQuery = tagQueryString ? uri.query : null
      def expectedUrl = URIUtils.buildURL(uri.scheme, uri.host, uri.port, uri.path)
      if (expectedQuery != null && !expectedQuery.empty) {
        expectedUrl = "$expectedUrl?$expectedQuery"
      }
      trace.span {
        childOf(trace.span(leafParentId))
        if (renameService) {
          serviceName("localhost")
        }
        operationName SpanNaming.instance().namingSchema().client().operationForComponent(NettyHttpClientDecorator.DECORATE.component().toString())
        resourceName "$method $uri.path"
        spanType DDSpanTypes.HTTP_CLIENT
        errored error
        measured true
        tags {
          "$Tags.COMPONENT" NettyHttpClientDecorator.DECORATE.component()
          "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
          "$Tags.PEER_HOSTNAME" "localhost"
          "$Tags.PEER_PORT" uri.port
          "$Tags.PEER_HOST_IPV4" { it == null || it == "127.0.0.1" } // Optional
          "$Tags.HTTP_URL" expectedUrl
          "$Tags.HTTP_METHOD" method
          if (status) {
            "$Tags.HTTP_STATUS" status
          }
          if (tagQueryString) {
            "$DDTags.HTTP_QUERY" expectedQuery
            "$DDTags.HTTP_FRAGMENT" { it == null || it == uri.fragment } // Optional
          }
          if (exception) {
            errorTags(exception.class, exception.message)
          }
          if ({ isDataStreamsEnabled() }) {
            "$DDTags.PATHWAY_HASH" { String }
          }
          defaultTags()
          if (extraTags) {
            it.addTags(extraTags)
          }
        }
      }
    }
  }

  @Override
  int size(int size) {
    return size + 1
  }

  boolean testRedirects() {
    false
  }

  boolean testConnectionFailure() {
    false
  }

  boolean testRemoteConnection() {
    // FIXME: figure out how to configure timeouts.
    false
  }

  static class CollectingFilter implements ExchangeFilterFunction {
    volatile String collected = ""
    volatile int count = 0

    @Override
    Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
      def span = activeSpan()
      collected += span == null ? "null:" : String.valueOf(span.getTag(Tags.COMPONENT)) + ":"
      count += 1
      return next.exchange(request)
    }
  }
}
