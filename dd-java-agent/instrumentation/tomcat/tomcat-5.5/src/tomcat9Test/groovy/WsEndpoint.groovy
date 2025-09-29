import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.OnClose
import javax.websocket.OnMessage
import javax.websocket.OnOpen
import javax.websocket.Session
import java.nio.ByteBuffer

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WsEndpoint {
  static volatile Session activeSession = null

  static void logReadSpan() {
    runUnderTrace("onRead", {})
  }

  abstract static class StdEndpoint extends Endpoint {
    @Override
    void onOpen(Session session, EndpointConfig endpointConfig) {
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

  static class StdPartialEndpoint extends StdEndpoint {
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
      super.onOpen(session, endpointConfig)
    }
  }

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

