package dd.trace.instrumentation.springwebflux.server;

import datadog.trace.api.Trace;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import reactor.core.publisher.Mono;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

@SpringBootApplication
public class SpringWebFluxTestApplication {

  @Bean
  HandlerMapping wsHandlerMapping(WsHandler wsHandler) {
    Map<String, WebSocketHandler> map = new HashMap<>();
    map.put("/websocket", wsHandler);
    SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
    handlerMapping.setOrder(1);
    handlerMapping.setUrlMap(map);
    return handlerMapping;
  }

  @Bean
  RouterFunction<ServerResponse> echoRouterFunction(EchoHandler echoHandler) {
    return RouterFunctions.route(POST("/echo"), new EchoHandlerFunction(echoHandler));
  }

  @Bean
  RouterFunction<ServerResponse> greetRouterFunction(GreetingHandler greetingHandler) {
    return RouterFunctions.route(GET("/greet"), request -> greetingHandler.defaultGreet())
        .andRoute(GET("/greet/{name}"), greetingHandler::customGreet)
        .andRoute(GET("/greet/{name}/{word}"), greetingHandler::customGreetWithWord)
        .andRoute(GET("/double-greet"), request -> greetingHandler.doubleGreet())
        .andRoute(GET("/greet-delayed"), request -> greetingHandler.defaultGreet().delayElement(Duration.ofMillis(100)))
        .andRoute(GET("/greet-failfast/{id}"), request -> { throw new RuntimeException("bad things happen"); })
        .andRoute(GET("/greet-failmono/{id}"), request -> Mono.error(new RuntimeException("bad things happen")))
        .andRoute(GET("/greet-traced-method/{id}"), request ->
            greetingHandler.intResponse(Mono.just(tracedMethod(Long.parseLong(request.pathVariable("id"))))))
        .andRoute(GET("/greet-mono-from-callable/{id}"), request ->
            greetingHandler.intResponse(Mono.fromCallable(() -> tracedMethod(Long.parseLong(request.pathVariable("id"))))))
        .andRoute(GET("/greet-delayed-mono/{id}"), request ->
            greetingHandler.intResponse(
                Mono.just(Long.parseLong(request.pathVariable("id")))
                    .delayElement(Duration.ofMillis(100))
                    .map(SpringWebFluxTestApplication::tracedMethod)));
  }

  @Component
  public class GreetingHandler {
    static final String DEFAULT_RESPONSE = "HELLO";

    Mono<ServerResponse> defaultGreet() {
      return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
          .body(BodyInserters.fromValue(DEFAULT_RESPONSE));
    }

    Mono<ServerResponse> doubleGreet() {
      return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
          .body(BodyInserters.fromValue(DEFAULT_RESPONSE + DEFAULT_RESPONSE));
    }

    Mono<ServerResponse> customGreet(ServerRequest request) {
      return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
          .body(BodyInserters.fromValue(DEFAULT_RESPONSE + " " + request.pathVariable("name")));
    }

    Mono<ServerResponse> customGreetWithWord(ServerRequest request) {
      return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
          .body(BodyInserters.fromValue(DEFAULT_RESPONSE + " " + request.pathVariable("name") + " " + request.pathVariable("word")));
    }

    Mono<ServerResponse> intResponse(Mono<FooModel> mono) {
      return ServerResponse.ok().contentType(MediaType.TEXT_PLAIN)
          .body(BodyInserters.fromPublisher(mono.map(i -> DEFAULT_RESPONSE + " " + i.getId()), String.class));
    }
  }

  @Trace
  private static FooModel tracedMethod(long id) {
    return new FooModel(id, "tracedMethod");
  }
}
