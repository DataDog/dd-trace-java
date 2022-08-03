package com.example.k8slibinjectionapp;

import static org.springframework.web.reactive.function.server.ServerResponse.ok;

import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public class HelloHandler {
    private HelloService helloService;

    public HelloHandler(HelloService helloService) {
        this.helloService = helloService;
    }

    public Mono<ServerResponse> hello(ServerRequest request) {
        return ok().bodyValue(helloService.generateMessage());
    }

    public Mono<ServerResponse> json(ServerRequest request) {
        return ok().bodyValue(new Hello(helloService.generateMessage()));
    }
}
