import datadog.trace.agent.test.base.WebsocketServer
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.MultipartConfigElement
import jakarta.servlet.Servlet
import jakarta.servlet.ServletException
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.websocket.CloseReason
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.MessageHandler
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.ServerEndpoint
import jakarta.websocket.server.ServerEndpointConfig
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.server.session.SessionHandler
import org.eclipse.jetty.servlet.FilterMapping
import org.eclipse.jetty.servlet.ServletContextHandler
import org.eclipse.jetty.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer

import java.nio.ByteBuffer

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JettyServer implements WebsocketServer {
  def port = 0
  final server = new Server(0) // select random open port

  JettyServer(Handler handler, usePojoWebsocketHandler = false) {
    server.handler = handler
    server.addBean(errorHandler)
    def endpointClass = usePojoWebsocketHandler ? PojoEndpoint : WsEndpoint

    if (handler instanceof ServletContextHandler) {
      try {
        JakartaWebSocketServletContainerInitializer.configure(handler, (servletContext, container) -> {
          container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/websocket").build())
        })
      } catch (Throwable ignored) {
      }
    }
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
    ServletContextHandler handler = new ServletContextHandler(null, "/context-path")
    final sessionHandler = new SessionHandler()
    handler.sessionHandler = sessionHandler
    handler.errorHandler = errorHandler
    handler.servletHandler.addFilterWithMapping(EnableMultipartFilter, '/*', FilterMapping.ALL)
    handler.servletHandler.addServletWithMapping(servlet, '/*')
    handler
  }

  static class EnableMultipartFilter implements Filter {
    static final MultipartConfigElement MULTIPART_CONFIG_ELEMENT = new MultipartConfigElement(System.getProperty('java.io.tmpdir'))

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
      request.setAttribute('org.eclipse.jetty.multipartConfig', MULTIPART_CONFIG_ELEMENT)
      chain.doFilter(request, response)
    }
  }

  static errorHandler = new ErrorHandler() {
    @Override
    protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
      Throwable th = (Throwable) request.getAttribute("jakarta.servlet.error.exception")
      message = th ? th.message : message
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
        binaries.each { stream.write(it) }
      }
    }
  }

  @Override
  synchronized void awaitConnected() {
    synchronized (Lock) {
      try {
        while (Lock.activeSession == null) {
          Lock.wait()
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
