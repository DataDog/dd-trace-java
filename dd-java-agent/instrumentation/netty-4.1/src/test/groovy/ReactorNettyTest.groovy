import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientResponse
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ReactorNettyTest extends AgentTestRunner {
  @Override
  boolean useStrictTraceWrites() {
    // TODO fix this by making sure that spans get closed properly
    return false
  }

  @AutoCleanup
  @Shared
  def server = httpServer {
    handlers {
      prefix("success") {
        response.status(200).send("Hello.")
      }
    }
  }

  private int doRequest() {
    HttpClientResponse resp = HttpClient.create()
      .baseUrl(server.address.toString())
      .get()
      .uri("/success")
      .response()
      .block()
    return resp.status().code()
  }

  def "two basic GET requests #url"() {
    when:
    runUnderTrace("parent") {
      doRequest()
    }
    runUnderTrace("parent") {
      doRequest()
    }

    then:
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "parent")
        clientSpan(it, 1, span(0))
      }
      trace(2) {
        basicSpan(it, "parent")
        clientSpan(it, 1, span(0))
      }
    }
  }

  void clientSpan(TraceAssert trace, int index, Object parentSpan, String method = "GET", URI uri = server.address.resolve("/success"), Integer status = 200) {
    trace.span {
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      operationName "netty.client.request"
      resourceName "$method $uri.path"
      spanType DDSpanTypes.HTTP_CLIENT
      errored false
      tags {
        "$Tags.COMPONENT" "netty-client"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" uri.host

        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" uri.port
        "$Tags.HTTP_URL" "${uri.resolve(uri.path)}"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" status
        defaultTags()
      }
    }
  }

}
