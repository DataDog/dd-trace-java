package server

import com.datadog.iast.test.IastHttpServerTest
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.internal.VertxInternal
import okhttp3.Request

import java.util.concurrent.CompletableFuture

class IastVertxSinksTest extends IastHttpServerTest<Vertx40Server> {

  @Override
  HttpServer server() {
    return new Vertx40Server()
  }



  void 'test unvalidated redirect reroute1'() {
    given:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/sinks/reroute1?path=rerouted"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.onRedirect("rerouted")
  }

  void 'test unvalidated redirect reroute2'() {
    given:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/sinks/reroute2?path=rerouted"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.onRedirect("rerouted")
  }

  void 'test unvalidated redirect location header'() {
    given:
    final module = Mock(UnvalidatedRedirectModule)
    InstrumentationBridge.registerIastModule(module)
    final url = "${address}/iast/sinks/redirectheader?name=Location&value=path"
    final request = new Request.Builder().url(url).build()

    when:
    client.newCall(request).execute()

    then:
    1 * module.onHeader("Location", "path")
  }

  private class Vertx40Server implements HttpServer {
    private VertxInternal server
    private int port = 0

    @Override
    void start() {
      server = Vertx.vertx()
      final future = new CompletableFuture<>()
      server.eventBus().localConsumer('PORT_DATA')
        .handler({ message ->
          port = message.body()
          message.reply(null)
          future.complete(null)
        })
      final deployment = new DeploymentOptions()
        .setInstances(1)
      server.deployVerticle('server.IastSinksVerticle', deployment).await()
      future.get()
    }

    @Override
    void stop() {
      server.close()
    }

    @Override
    URI address() {
      return new URI("http://localhost:$port/")
    }
  }
}
