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
import javax.websocket.OnClose
import javax.websocket.OnMessage
import javax.websocket.OnOpen
import javax.websocket.Session
import javax.websocket.server.ServerEndpoint
import javax.websocket.server.ServerEndpointConfig
import java.nio.ByteBuffer

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.UNKNOWN
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JettyServer implements WebsocketServer {
  def port = 0
  final server = new Server(0) // select random open port

  JettyServer(ServletContextHandler handler, usePojoWebsocketHandler = false) {
    final sessionHandler = new SessionHandler()
    sessionHandler.handler = handler
    server.handler = sessionHandler
    server.addBean(errorHandler)
    server.addBean(sessionHandler.sessionIdManager, true)
    def endpointClass = usePojoWebsocketHandler ? PojoEndpoint : WsEndpoint
    JavaxWebSocketServletContainerInitializer.configure(handler, (servletContext, container) -> {
      container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/websocket").build())
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
      Lock.activeSession.getBasicRemote().sendText(messages[0])
    } else {
      def remoteEndpoint = Lock.activeSession.getBasicRemote()
      for (int i = 0; i < messages.length; i++) {
        remoteEndpoint.sendText(messages[i], i == messages.length - 1)
      }
    }
  }

  @Override
  void serverSendBinary(byte[][] binaries) {
    if (binaries.length == 1) {
      Lock.activeSession.getBasicRemote().sendBinary(ByteBuffer.wrap(binaries[0]))
    } else {
      try (def stream = Lock.activeSession.getBasicRemote().getSendStream()) {
        binaries.each {
          stream.write(it)
        }
      }
    }
  }

  @Override
  synchronized void awaitConnected() {
    synchronized (Lock) {
      try {
        while (Lock.activeSession == null) {
          Lock.wait(1000)
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt()
      }
    }
  }

  @Override
  void serverClose() {
    Lock.activeSession?.close()
    Lock.activeSession = null
  }

  @Override
  void setMaxPayloadSize(int size) {
    Lock.activeSession?.setMaxTextMessageBufferSize(size)
    Lock.activeSession?.setMaxBinaryMessageBufferSize(size)
  }

  @Override
  boolean canSplitLargeWebsocketPayloads() {
    false
  }

  static class Lock {
    static Session activeSession
  }

  @ServerEndpoint("/websocket")
  static class PojoEndpoint {

    @OnOpen
    void onOpen(Session session) {
      Lock.activeSession = session
      synchronized (Lock) {
        Lock.notifyAll()
      }
    }

    @OnMessage
    void onText(String text, Session session, boolean last) {
      runUnderTrace("onRead", {})
    }

    @OnMessage
    void onBytes(byte[] binary) {
      runUnderTrace("onRead", {})
    }

    @OnClose
    void onClose() {
      Lock.activeSession = null
    }
  }

  static class WsEndpoint extends Endpoint {

    @Override
    void onOpen(Session session, EndpointConfig endpointConfig) {
      session.addMessageHandler(new MessageHandler.Partial<String>() {
        @Override
        void onMessage(String s, boolean b) {
          runUnderTrace("onRead", {})
        }
      })
      session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {

        @Override
        void onMessage(ByteBuffer buffer) {
          runUnderTrace("onRead", {})
        }
      })
      Lock.activeSession = session
      synchronized (Lock) {
        Lock.notifyAll()
      }
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
      Lock.activeSession = null
    }
  }
}
