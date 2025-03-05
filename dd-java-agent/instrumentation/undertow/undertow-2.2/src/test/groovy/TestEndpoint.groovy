import jakarta.websocket.OnClose
import jakarta.websocket.OnMessage
import jakarta.websocket.OnOpen
import jakarta.websocket.Session
import jakarta.websocket.server.ServerEndpoint

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@ServerEndpoint("/websocket")
class TestEndpoint {
  static volatile Session activeSession

  @OnOpen
  void onOpen(Session session) {
    activeSession = session
    synchronized (TestEndpoint) {
      TestEndpoint.notifyAll()
    }
  }

  @OnClose
  void onClose() {
    activeSession = null
  }

  @OnMessage
  void onMessage(String s, boolean last) {
    runUnderTrace("onRead", {})
  }

  @OnMessage
  void onMessage(byte[] b, boolean last) {
    runUnderTrace("onRead", {})
  }
}
