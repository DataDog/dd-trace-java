package test.boot

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebsocketConfig implements WebSocketConfigurer  {
  @Bean
  WebsocketEndpoint websocketEndpoint() {
    new WebsocketEndpoint()
  }

  @Override
  void registerWebSocketHandlers(WebSocketHandlerRegistry webSocketHandlerRegistry) {
    webSocketHandlerRegistry.addHandler(websocketEndpoint(), "/websocket")
  }
}
