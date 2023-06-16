package test.boot

import datadog.trace.agent.test.base.HttpServer
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.embedded.EmbeddedWebApplicationContext

class SpringBootServer implements HttpServer {
  def port = 0
  final SpringApplication app
  EmbeddedWebApplicationContext context
  String servletContext

  SpringBootServer(SpringApplication app, String servletContext) {
    this.app = app
    this.servletContext = servletContext
  }

  @Override
  void start() {
    app.setDefaultProperties(["server.port": 0, "server.context-path": "/$servletContext"])
    context = app.run() as EmbeddedWebApplicationContext
    port = context.embeddedServletContainer.port
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
}
