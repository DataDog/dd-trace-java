package com.example;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.ext.web.Router;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

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
}
