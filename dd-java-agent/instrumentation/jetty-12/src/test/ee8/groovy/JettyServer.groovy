import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.WebsocketServer
import org.eclipse.jetty.ee8.nested.ErrorHandler
import org.eclipse.jetty.ee8.nested.SessionHandler as EE8SessionHandler
import org.eclipse.jetty.ee8.servlet.ServletContextHandler
import org.eclipse.jetty.ee8.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.session.SessionHandler

import javax.servlet.Servlet
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.Session
import javax.websocket.server.ServerEndpointConfig
import java.nio.ByteBuffer

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JettyServer implements WebsocketServer {
  def port = 0
  final server = new Server(0) // select random open port

  JettyServer(ServletContextHandler handler) {
    final sessionHandler = new SessionHandler()
    sessionHandler.handler = handler
    server.handler = sessionHandler
    server.addBean(errorHandler)
    server.addBean(sessionHandler.sessionIdManager, true)
    JavaxWebSocketServletContainerInitializer.configure(handler, (servletContext, container) -> {
      container.addEndpoint(ServerEndpointConfig.Builder.create(WsEndpoint.class, "/websocket").build())
    })
  }

  @Override
  void start() {
    server.start()
    port = server.connectors[0].localPort
    assert port > 0
  }

  @Override
  void stop() {
    server.stop()
  }

  @Override
  URI address() {
    return new URI("http://localhost:$port/context-path/")
  }

  @Override
  String toString() {
    return this.class.name
  }

  static ServletContextHandler servletHandler(Class<? extends Servlet> servlet) {
    // defaults to jakarta servlet
    ServletContextHandler handler = new ServletContextHandler(null, "/context-path")
    handler.sessionHandler = new EE8SessionHandler()
    handler.errorHandler = errorHandler
    HttpServerTest.ServerEndpoint.values()
      .findAll { !(it in [NOT_FOUND, UNKNOWN]) }
      .each {
        handler.servletHandler.addServletWithMapping(servlet, it.path)
      }
    handler
  }

  static errorHandler = new ErrorHandler() {
    @Override
    protected void writeErrorPage(HttpServletRequest request, Writer writer, int code,
      String message, boolean showStacks) throws IOException {
      ServletException th = (ServletException) request.getAttribute("javax.servlet.error.exception")
      message = th ? th.getRootCause().message : message
      if (message) {
        writer.write(message)
      }
    }
  }

  @Override
  void serverSendText(String[] messages) {
    if (messages.length == 1) {
      WsEndpoint.activeSession.getBasicRemote().sendText(messages[0])
    } else {
      def remoteEndpoint = WsEndpoint.activeSession.getBasicRemote()
      for (int i = 0; i < messages.length; i++) {
        remoteEndpoint.sendText(messages[i], i == messages.length - 1)
      }
    }
  }

  @Override
  void serverSendBinary(byte[][] binaries) {
    if (binaries.length == 1) {
      WsEndpoint.activeSession.getBasicRemote().sendBinary(ByteBuffer.wrap(binaries[0]))
    } else {
      try (def stream = WsEndpoint.activeSession.getBasicRemote().getSendStream()) {
        binaries.each { stream.write(it) }
      }
    }
  }

  @Override
  synchronized void awaitConnected() {
    synchronized (WsEndpoint) {
      try {
        while (WsEndpoint.activeSession == null) {
          WsEndpoint.wait()
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt()
      }
    }
  }

  @Override
  void serverClose() {
    WsEndpoint.activeSession?.close()
    WsEndpoint.activeSession = null
  }

  @Override
  void setMaxPayloadSize(int size) {
    WsEndpoint.activeSession?.setMaxTextMessageBufferSize(size)
    WsEndpoint.activeSession?.setMaxBinaryMessageBufferSize(size)
  }

  @Override
  boolean canSplitLargeWebsocketPayloads() {
    false
  }

  static class WsEndpoint extends Endpoint {
    static Session activeSession

    @Override
    void onOpen(Session session, EndpointConfig endpointConfig) {
      session.addMessageHandler(new MessageHandler.Partial<String>() {
          @Override
          void onMessage(String s, boolean last) {
            // jetty does not respect at all limiting the payload so we have to simulate it in few tests
            runUnderTrace("onRead", {})
          }
        })
      session.addMessageHandler(new MessageHandler.Partial<byte[]>() {
          @Override
          void onMessage(byte[] b, boolean last) {
            runUnderTrace("onRead", {})
          }
        })
      activeSession = session
      synchronized (WsEndpoint) {
        WsEndpoint.notifyAll()
      }
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
      activeSession = null
    }
  }
}
