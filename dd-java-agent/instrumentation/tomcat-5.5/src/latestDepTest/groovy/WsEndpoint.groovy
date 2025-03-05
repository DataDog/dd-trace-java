import jakarta.websocket.CloseReason
import jakarta.websocket.Endpoint
import jakarta.websocket.EndpointConfig
import jakarta.websocket.MessageHandler
import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.ServerEndpoint

import java.nio.ByteBuffer

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WsEndpoint {
  static volatile Session activeSession = null

  static void logReadSpan() {
    runUnderTrace("onRead", {})
  }


  static class StdPartialEndpoint extends Endpoint {
    @Override
    void onOpen(Session session, EndpointConfig endpointConfig) {
      session.addMessageHandler(new MessageHandler.Partial<String>() {
          @Override
          void onMessage(String s, boolean last) {
            logReadSpan()
          }
        })
      session.addMessageHandler(new MessageHandler.Partial<byte[]>() {
          @Override
          void onMessage(byte[] buffer, boolean last) {
            logReadSpan()
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

  @ServerEndpoint("/websocket")
  static class PojoEndpoint {
    @OnOpen
    void onOpen(Session session, EndpointConfig endpointConfig) {
      activeSession = session
      synchronized (WsEndpoint) {
        WsEndpoint.notifyAll()
      }
    }

    @OnClose
    void onClose(Session session, CloseReason closeReason) {
      activeSession = null
    }

    @OnMessage
    void onTextMessage(String message, boolean last, Session session) {
      logReadSpan()
    }

    @OnMessage
    void onBinaryMessage(ByteBuffer message, boolean last, Session session) {
      logReadSpan()
    }
  }
}

