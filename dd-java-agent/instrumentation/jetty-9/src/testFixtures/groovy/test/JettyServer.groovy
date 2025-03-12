package test

import datadog.trace.agent.test.base.WebsocketServer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler

import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.Session
import javax.websocket.server.ServerEndpointConfig
import java.nio.ByteBuffer

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JettyServer implements WebsocketServer {
  def port = 0
  final server = new Server(0) // select random open port
  def websocketAvailable = true

  JettyServer(AbstractHandler handler) {
    server.setHandler(handler)
    try {
      def container = ("org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer" as Class)."configureContext"(handler)
      container."addEndpoint"(ServerEndpointConfig.Builder.create(WsEndpoint.class, "/websocket").build())

    } catch (Throwable t) {
      try {
        ("org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer" as Class)."configure"(handler, { servletContext, container ->
          container.addEndpoint(ServerEndpointConfig.Builder.create(WsEndpoint.class, "/websocket").build())
        })

      } catch (Throwable tt) {
        websocketAvailable = false
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
    new URI("http://localhost:$port/")
  }

  @Override
  String toString() {
    this.class.name
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
