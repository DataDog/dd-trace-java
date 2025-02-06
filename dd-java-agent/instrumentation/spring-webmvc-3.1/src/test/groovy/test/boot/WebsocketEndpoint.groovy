package test.boot

import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.AbstractWebSocketHandler

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
    System.err.println("SETTING SESSION on $this")
    activeSession = session
    try (def ignored = AgentTracer.get().muteTracing()) {
      session.sendMessage(new TextMessage("READY"))
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    System.err.println("received text ${message.getPayload()}")
  }

  @Override
  protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
    System.err.println("received binary ${message.getPayload()}")
  }
}
