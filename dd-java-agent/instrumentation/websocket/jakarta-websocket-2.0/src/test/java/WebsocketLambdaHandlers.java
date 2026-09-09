import jakarta.websocket.MessageHandler;

final class WebsocketLambdaHandlers {
  private WebsocketLambdaHandlers() {}

  static MessageHandler.Whole<String> whole() {
    return message -> {};
  }

  static MessageHandler.Partial<String> partial() {
    return (message, last) -> {};
  }
}
