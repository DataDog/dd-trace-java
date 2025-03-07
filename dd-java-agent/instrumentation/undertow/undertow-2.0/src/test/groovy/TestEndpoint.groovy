import javax.websocket.OnClose
import javax.websocket.OnMessage
import javax.websocket.OnOpen
import javax.websocket.Session
import javax.websocket.server.ServerEndpoint

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
