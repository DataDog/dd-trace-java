import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.core.DDSpan
import org.eclipse.jetty.ee8.websocket.javax.common.UpgradeRequest
import org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer
import org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerFrameHandlerFactory
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
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL

class EE8WebsocketTest extends InstrumentationSpecification {

  def "test jetty advices with endpoint class #endpoint.class and message type #msgType"() {
    setup:
    def url = "ws://inmemory/test/param"
    JavaxWebSocketServerFrameHandlerFactory factory = new JavaxWebSocketServerFrameHandlerFactory(new JavaxWebSocketServerContainer(new WebSocketMappings(), new WebSocketComponents(), null))
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
    new JavaxEndpoints.TestEndpoint(new JavaxEndpoints.FullStringHandler())        | "text"
    new JavaxEndpoints.TestEndpoint(new JavaxEndpoints.PartialStringHandler())     | "text"
    new JavaxEndpoints.TestEndpoint(new JavaxEndpoints.FullBytesHandler())         | "binary"
    new JavaxEndpoints.TestEndpoint(new JavaxEndpoints.FullByteBufferHandler())    | "binary"
    new JavaxEndpoints.TestEndpoint(new JavaxEndpoints.PartialBytesHandler())      | "binary"
    new JavaxEndpoints.TestEndpoint(new JavaxEndpoints.PartialByteBufferHandler()) | "binary"
    new JavaxEndpoints.PojoFullEndpoint()                                          | "text"
    new JavaxEndpoints.PojoFullEndpoint()                                          | "binary"
    new JavaxEndpoints.PojoPartialEndpoint()                                       | "text"
    new JavaxEndpoints.PojoPartialEndpoint()                                       | "binary"
  }
}
