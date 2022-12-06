package com.example.k8slibinjectionapp;

import static org.springframework.fu.jafu.Jafu.reactiveWebApplication;
import static org.springframework.fu.jafu.webflux.WebFluxServerDsl.webFlux;

import org.springframework.fu.jafu.JafuApplication;

public class K8sLibInjectionAppApplication {
    public static JafuApplication app = reactiveWebApplication(a -> a
            .beans(b -> b
                    .bean(HelloHandler.class)
                    .bean(HelloService.class))
            .enable(webFlux(s -> s
                    .port(s.env().getProperty("server.port", Integer.class, 8080))
                    .router(r -> {
                        HelloHandler handler = s.ref(HelloHandler.class);
                        r
                                .GET("/", handler::hello)
                                .GET("/api", handler::json);
                    }).codecs(c -> c
                            .string()
                            .jackson()))));

    public static void main (String[] args) {
        app.run(args);
    }
}
