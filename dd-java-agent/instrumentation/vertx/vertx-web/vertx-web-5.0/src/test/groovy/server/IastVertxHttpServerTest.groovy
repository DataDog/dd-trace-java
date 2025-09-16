package server

import com.datadog.iast.test.IastSourcesTest
import datadog.trace.agent.test.base.HttpServer
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServerRequest
import io.vertx.core.json.JsonObject
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody

import java.util.concurrent.CompletableFuture

abstract class IastVertxHttpServerTest extends IastSourcesTest<IastVertxServer> {

  @Override
  HttpServer server() {
    return new IastVertxServer()
  }

  boolean isHttps() {
    false
  }

  void 'test event bus'() {
    when:
    final url = "${address}/iast/sources/eventBus"
    final body = RequestBody.create(MediaType.get('application/json'), '{ "name": "value" }')
    final request = new Request.Builder().url(url).post(body).build()
    final response = client.newCall(request).execute()

    then:
    response.code() == 200
    response.body().string() == 'OK'
  }

  class IastVertxServer implements HttpServer {
    private Vertx server
    private port

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
        .setConfig(new JsonObject().put('https', isHttps()))
      server.deployVerticle('server.IastSourcesVerticle', deployment).await()
      future.get()
    }

    @Override
    void stop() {
      server.close()
    }

    @Override
    URI address() {
      return new URI("http${https ? 's' : ''}://localhost:$port/")
    }
  }

  // Cookies not supported in Vert.x 4.0.0
  @Override
  protected boolean ignoreCookies() {
    final hasCookies = HttpServerRequest.declaredMethods.any { it.name == 'cookies' }
    return !hasCookies
  }
}
