import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.handler.timeout.ReadTimeoutHandler
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientResponse
import spock.lang.AutoCleanup
import spock.lang.Shared

import java.util.concurrent.TimeUnit

import static datadog.trace.agent.test.base.HttpClientTest.READ_TIMEOUT_MS
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
      prefix("timeout") {
        Thread.sleep(READ_TIMEOUT_MS + 1000)
        response.status(200).send("Timeout.")
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

  private doTimeoutRequest() {
    HttpClient client = HttpClient.create()
      // This could be replaced with responseTimeout(Duration.ofMillis(READ_TIMEOUT_MS)) in newer releases of reactor-netty
      .doAfterRequest({ r, c -> c.addHandler("TimeoutHandler", new ReadTimeoutHandler(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)) })
      .baseUrl(server.address.toString())

    try {
      client.get()
        .uri("/timeout")
        .response()
        .block()
        .status()
        .code()
      throw new RuntimeException("This should not get executed")
    } catch (ReadTimeoutException ignore) {
      // this test expects this exception
    } catch (Throwable t) {
      throw t
    }
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

  def "test timeout"() {
    when:
    runUnderTrace("parent") {
      doTimeoutRequest()
    }

    then:
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        clientSpan(it, 1, span(0), "GET", server.address.resolve("/timeout"), null, true, ReadTimeoutException)
      }
    }
  }

  void clientSpan(TraceAssert trace, int index, Object parentSpan, String method = "GET", URI uri = server.address.resolve("/success"), Integer status = 200, boolean error = false, Class errorType = null) {
    trace.span {
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      operationName "netty.client.request"
      resourceName "$method $uri.path"
      spanType DDSpanTypes.HTTP_CLIENT
      errored error
      tags {
        "$Tags.COMPONENT" "netty-client"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" uri.host

        "$Tags.PEER_HOST_IPV4" "127.0.0.1"
        "$Tags.PEER_PORT" uri.port
        "$Tags.HTTP_URL" "${uri.resolve(uri.path)}"
        "$Tags.HTTP_METHOD" method
        "$Tags.HTTP_STATUS" status
        if (error) {
          "error.type" errorType.name
          "error.stack" String
        }
        defaultTags()
      }
    }
  }
}
