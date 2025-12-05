package test

import datadog.trace.agent.test.base.WebsocketServer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler

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

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class JettyServer implements WebsocketServer {
  def port = 0
  final server = new Server(0) // select random open port
  def websocketAvailable = true

  JettyServer(AbstractHandler handler, final boolean usePojoWebsocketHandler = false) {
    server.setHandler(handler)
    def endpointClass = usePojoWebsocketHandler ? PojoEndpoint : WsEndpoint
    try {
      def container = ("org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer" as Class)."configureContext"(handler)
      container."addEndpoint"(ServerEndpointConfig.Builder.create(endpointClass, "/websocket").build())
    } catch (Throwable ignored) {
      try {
        ("org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer" as Class)."configure"(handler, { servletContext, container ->
          container.addEndpoint(ServerEndpointConfig.Builder.create(endpointClass, "/websocket").build())
        })
      } catch (Throwable ignored2) {
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
