package datadog.smoketest.springboot.controller;

import static org.springframework.web.reactive.function.server.RouterFunctions.*;

import datadog.smoketest.springboot.model.Fruit;
import datadog.smoketest.springboot.repository.FruitRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@Configuration
public class FruitRouter {
  @Bean
  RouterFunction<ServerResponse> routes(final FruitRepository repository) {
    return route(
            RequestPredicates.GET("/fruits"),
            request ->
                ServerResponse.ok()
                    .body(Mono.fromSupplier(() -> repository.findAll()), Fruit.class))
        .and(
            route(
                RequestPredicates.GET("/fruits/{name}"),
                request ->
                    ServerResponse.ok()
                        .body(
                            Mono.fromSupplier(
                                () -> repository.findByName(request.pathVariable("name"))),
                            Fruit.class)));
  }
}
