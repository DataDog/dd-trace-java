import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.DDSpanTypes
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.handler.ContextHandler
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.client.WebSocketClient
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler

import static java.util.concurrent.TimeUnit.SECONDS

class JettyWebSocketUpgradeTest extends InstrumentationSpecification {

  def "HTTP client span finishes when the WebSocket upgrade succeeds"() {
    setup:
    def server = new Server(0)
    def context = new ContextHandler("/")
    server.handler = context
    context.handler = WebSocketUpgradeHandler.from(server, context).configure { container ->
      container.addMapping("/upgrade", { request, response, callback -> new Endpoint() })
    }
    def client = new WebSocketClient()
    server.start()
    client.start()
    def uri = URI.create("ws://localhost:${((ServerConnector) server.connectors[0]).localPort}/upgrade")

    when:
    def session = client.connect(new Endpoint(), uri).get(5, SECONDS)

    then:
    session.open
    // The HTTP handshake must be reported before the WebSocket connection closes.
    assertTraces(1) {
      trace(1) {
        span {
          operationName "http.request"
          resourceName "GET /upgrade"
          spanType DDSpanTypes.HTTP_CLIENT
          parent()
          errored false
          tags(false) {
            "component" "jetty-client"
            "span.kind" "client"
            "http.status_code" 101
          }
        }
      }
    }

    cleanup:
    try {
      client.stop()
    } finally {
      server.stop()
    }
  }

  static class Endpoint implements Session.Listener.AutoDemanding {
  }
}
