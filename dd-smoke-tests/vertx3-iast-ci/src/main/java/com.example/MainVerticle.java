package com.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Router;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MainVerticle extends AbstractVerticle {

    @Override
    public void start(Future<Void> startFuture) {
        Router router = Router.router(vertx);

        router.get("/greetings").handler(ctx -> {
            String param = ctx.request().getParam("param");
            if (param == null) {
                ctx.response()
                        .setStatusCode(400)
                        .end("Missing 'param' query parameter");
            } else {
                ctx.response()
                        .putHeader("content-type", "text/plain")
                        .end("Hello " + param);
            }
        });

        router.get("/insecure_hash").handler(ctx -> {
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                ctx.response()
                        .putHeader("content-type", "text/plain")
                        .end("insecure hash");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        });

      router.get("/cmd_injection").handler(ctx -> {
        final HttpServerRequest request = ctx.request();
        final String param = request.getParam("param");
        runProcess(param);
        ctx.response().end("cmd injection");
      });

        // Iniciar servidor HTTP
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(8080, http -> {
                    if (http.succeeded()) {
                        startFuture.complete();
                        System.out.println("HTTP server started on port 8080");
                    } else {
                        startFuture.fail(http.cause());
                    }
                });
    }

  private void runProcess(final String cmd) {
    Process process = null;
    try {
      process =Runtime.getRuntime().exec(cmd);
    } catch (final Throwable e) {
      // ignore it
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
