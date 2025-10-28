import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.DDSpan
import org.eclipse.jetty.ee10.websocket.jakarta.common.UpgradeRequest
import org.eclipse.jetty.ee10.websocket.jakarta.server.JakartaWebSocketServerContainer
import org.eclipse.jetty.ee10.websocket.jakarta.server.JakartaWebSocketServerFrameHandlerFactory
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.websocket.core.CloseStatus
import org.eclipse.jetty.websocket.core.CoreSession
import org.eclipse.jetty.websocket.core.Frame
import org.eclipse.jetty.websocket.core.OpCode
import org.eclipse.jetty.websocket.core.WebSocketComponents
import org.eclipse.jetty.websocket.core.server.WebSocketMappings

import java.security.Principal

import static datadog.trace.agent.test.base.HttpServerTest.websocketCloseSpan
import static datadog.trace.agent.test.base.HttpServerTest.websocketReceiveSpan
import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.TRACE_WEBSOCKET_MESSAGES_ENABLED
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL

class EE10WebsocketTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TRACE_WEBSOCKET_MESSAGES_ENABLED, "true")
  }

  def "test jetty advices with endpoint class #endpoint.class and message type #msgType"() {
    setup:
    def url = "ws://inmemory/test/param"
    JakartaWebSocketServerFrameHandlerFactory factory = new JakartaWebSocketServerFrameHandlerFactory(new JakartaWebSocketServerContainer(new WebSocketMappings(), new WebSocketComponents(), null))
    UpgradeRequest request = new UpgradeRequest() {

        @Override
        Principal getUserPrincipal() {
          return null
        }

        @Override
        URI getRequestURI() {
          return URI.create(url)
        }

        @Override
        String getPathInContext() {
          return "/test/param"
        }
      }
    def frameHandler = factory.newJakartaWebSocketFrameHandler(endpoint, request)
    def session = new CoreSession.Empty()
    when:
    runUnderTrace("parent") {

      activeSpan().setTag(HTTP_URL, url)
      frameHandler.onOpen(session, Callback.NOOP)
    }
    frameHandler.onFrame(new Frame(msgType == "text" ? OpCode.TEXT : OpCode.BINARY, "hello world"), Callback.NOOP)
    frameHandler.onClose(CloseStatus.toFrame(CloseStatus.NORMAL, "bye"), Callback.NOOP)

    then:
    assertTraces(3) {
      DDSpan handshake
      trace(1) {
        handshake = span(0)
        basicSpan(it, "parent", "/test/param", null, null, [(HTTP_URL): url])
      }
      trace(1) {
        websocketReceiveSpan(it, handshake, msgType, 11)
      }
      trace(1) {
        websocketCloseSpan(it, handshake, false, 1000, "bye")
      }
    }
    where:
    endpoint                                                                       | msgType
    new JakartaEndpoints.TestEndpoint(new JakartaEndpoints.FullStringHandler())        | "text"
    new JakartaEndpoints.TestEndpoint(new JakartaEndpoints.PartialStringHandler())     | "text"
    new JakartaEndpoints.TestEndpoint(new JakartaEndpoints.FullBytesHandler())         | "binary"
    new JakartaEndpoints.TestEndpoint(new JakartaEndpoints.FullByteBufferHandler())    | "binary"
    new JakartaEndpoints.TestEndpoint(new JakartaEndpoints.PartialBytesHandler())      | "binary"
    new JakartaEndpoints.TestEndpoint(new JakartaEndpoints.PartialByteBufferHandler()) | "binary"
    new JakartaEndpoints.PojoFullEndpoint()                                          | "text"
    new JakartaEndpoints.PojoFullEndpoint()                                          | "binary"
    new JakartaEndpoints.PojoPartialEndpoint()                                       | "text"
    new JakartaEndpoints.PojoPartialEndpoint()                                       | "binary"
  }
}
