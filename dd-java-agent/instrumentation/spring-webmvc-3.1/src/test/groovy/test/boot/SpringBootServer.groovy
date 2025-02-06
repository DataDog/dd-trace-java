package test.boot


import datadog.trace.agent.test.base.WebsocketServer
import org.springframework.beans.BeansException
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession

class SpringBootServer implements WebsocketServer {
  def port = 0
  final SpringApplication app
  EmbeddedWebApplicationContext context
  String servletContext
  WebsocketEndpoint endpoint

  SpringBootServer(SpringApplication app, String servletContext) {
    this.app = app
    this.servletContext = servletContext
  }

  @Override
  void start() {
    app.setDefaultProperties(["server.port": 0, "server.context-path": "/$servletContext"])
    context = app.run() as EmbeddedWebApplicationContext
    port = context.embeddedServletContainer.port
    try {
      endpoint = context.getBean(WebsocketEndpoint)
    } catch (BeansException ignored) {
      // silently ignore since not all the tests are deploying this endpoint
    }
    System.err.println("SET endpoint to $endpoint")
    assert port > 0
  }

  @Override
  void stop() {
    context.close()
  }

  @Override
  URI address() {
    new URI("http://localhost:$port/$servletContext/")
  }

  @Override
  String toString() {
    this.class.name
  }

  @Override
  void serverSendText(String[] messages) {
    WebSocketSession session = endpoint?.activeSession
    System.err.println("endpoint $endpoint")
    System.err.println("SESSION $session want to send $messages")
    if (session != null) {
      messages.each {
        session.sendMessage(new TextMessage(it))
      }
    }
  }

  @Override
  void serverSendBinary(byte[][] binaries) {
    WebSocketSession session = endpoint?.activeSession
    if (session != null) {
      binaries.each {
        session.sendMessage(new BinaryMessage(it))
      }
    }
  }

  @Override
  void serverClose() {
    System.err.println("CALLING CLOSE")
    endpoint?.activeSession?.close()
  }

  @Override
  void setMaxPayloadSize(int size) {
    endpoint?.activeSession?.setBinaryMessageSizeLimit(size)
    endpoint?.activeSession?.setTextMessageSizeLimit(size)
  }
}
