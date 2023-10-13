package test

import datadog.trace.agent.test.base.HttpServer
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.EmbeddedApplication
import io.micronaut.runtime.Micronaut
import io.micronaut.runtime.server.EmbeddedServer

class MicronautServer implements HttpServer {
  def port = 0
  ApplicationContext context

  @Override
  void start() {
    context = Micronaut.run(getClass())
    Optional<EmbeddedApplication> embeddedContainerBean = context.findBean(EmbeddedApplication)
    EmbeddedApplication app = embeddedContainerBean.orElse(null)
    if (app instanceof EmbeddedServer) {
      final EmbeddedServer embeddedServer = (EmbeddedServer) app
      port = embeddedServer.getPort()
    }
    assert port > 0
  }

  @Override
  void stop() {
    context.stop()
  }

  @Override
  URI address() {
    return new URI("http://localhost:$port/")
  }
}

