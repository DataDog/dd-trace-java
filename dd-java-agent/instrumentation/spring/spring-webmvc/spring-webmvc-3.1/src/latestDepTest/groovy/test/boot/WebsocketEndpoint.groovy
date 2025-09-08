package test.boot


import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.AbstractWebSocketHandler

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class WebsocketEndpoint extends AbstractWebSocketHandler {
  volatile WebSocketSession activeSession

  @Override
  boolean supportsPartialMessages() {
    true
  }

  @Override
  void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    activeSession = null
  }

  @Override
  void afterConnectionEstablished(WebSocketSession session) throws Exception {
    activeSession = session
    synchronized (WebsocketEndpoint) {
      WebsocketEndpoint.notifyAll()
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    runUnderTrace("onRead", {})
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
    runUnderTrace("onRead", {})
  }
}
