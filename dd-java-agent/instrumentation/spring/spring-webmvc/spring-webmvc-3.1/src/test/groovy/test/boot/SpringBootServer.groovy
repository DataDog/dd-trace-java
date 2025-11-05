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
    app.setDefaultProperties(["server.port": 0, "server.context-path": "/$servletContext", "server.forward-headers-strategy": "NONE"])
    context = app.run() as EmbeddedWebApplicationContext
    port = context.embeddedServletContainer.port
    try {
      endpoint = context.getBean(WebsocketEndpoint)
    } catch (BeansException ignored) {
      // silently ignore since not all the tests are deploying this endpoint
    }
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
    if (session != null) {
      if (messages.size() == 1) {
        session.sendMessage(new TextMessage(messages[0]))
      } else {
        for (def i = 0; i < messages.size(); i++) {
          session.sendMessage(new TextMessage(messages[i], i == messages.size() - 1))
        }
      }
    }
  }

  @Override
  void serverSendBinary(byte[][] binaries) {
    WebSocketSession session = endpoint?.activeSession
    if (session != null) {
      if (binaries.length == 1) {
        session.sendMessage(new BinaryMessage(binaries[0]))
      } else {
        for (def i = 0; i < binaries.length; i++) {
          session.sendMessage(new BinaryMessage(binaries[i], i == binaries.length - 1))
        }
      }
    }
  }

  @Override
  void serverClose() {
    endpoint?.activeSession?.close()
  }

  @Override
  synchronized void awaitConnected() {
    synchronized (WebsocketEndpoint) {
      try {
        while (endpoint?.activeSession == null) {
          WebsocketEndpoint.wait()
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt()
      }
    }
  }

  @Override
  void setMaxPayloadSize(int size) {
    endpoint?.activeSession?.setBinaryMessageSizeLimit(size)
    endpoint?.activeSession?.setTextMessageSizeLimit(size)
  }
}
