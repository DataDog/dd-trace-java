package datadog.vertx_3_4;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import java.math.BigInteger;
import java.util.concurrent.ThreadLocalRandom;

public class MainVerticle extends AbstractVerticle {

  public static void main(String[] args) throws Exception {
    VertxOptions options = new VertxOptions();
    options.setEventLoopPoolSize(1);
    options.setWorkerPoolSize(2);
    options.setInternalBlockingPoolSize(1);

    Vertx vertx = Vertx.vertx(options);
    MainVerticle verticle = new MainVerticle();
    vertx.deployVerticle(verticle);
  }

  static BigInteger randomFactorial() {
    int n = ThreadLocalRandom.current().nextInt(5, 100);
    BigInteger factorial = BigInteger.ONE;
    for (int i = 1; i <= n; i++) {
      factorial = factorial.multiply(BigInteger.valueOf(i));
    }
    return factorial;
  }

  @Override
  public void start(Future<Void> startPromise) throws Exception {
    Router router = Router.router(vertx);
    router
        .route("/routes")
        .handler(
            ctx ->
                ctx.response()
                    .setStatusCode(200)
                    .putHeader("content-type", "text/plain")
                    .end(randomFactorial().toString()));
    router
        .route("/api_security/sampling/:status_code")
        .handler(
            ctx ->
                ctx.response()
                    .setStatusCode(Integer.parseInt(ctx.request().getParam("status_code")))
                    .end("EXECUTED"));

    vertx
        .createHttpServer(new HttpServerOptions().setHandle100ContinueAutomatically(true))
        .requestHandler(
            req -> {
              if (req.path().startsWith("/routes") || req.path().startsWith("/api_security")) {
                router.accept(req);
              } else {
                req.response()
                    .putHeader("content-type", "text/plain")
                    .end(randomFactorial().toString());
              }
            })
        .listen(
            Integer.getInteger("vertx.http.port", 8080),
            http -> {
              if (http.succeeded()) {
                startPromise.complete();
                System.out.println("HTTP server started");
              } else {
                startPromise.fail(http.cause());
              }
            });
  }
}
