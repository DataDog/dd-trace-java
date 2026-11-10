package dd.trace.instrumentation.springwebflux.server;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Component
public class FunctionalRoutesComponent {
  @Bean
  public RouterFunction<ServerResponse> nestedRouterFunction() {
    return route()
        .path(
            "/nested",
            builder ->
                builder.GET(
                    "/{id}",
                    req -> ServerResponse.ok().bodyValue("nested " + req.pathVariable("id"))))
        .build();
  }

  @Bean
  public RouterFunction<ServerResponse> filteredRouterFunction() {
    return route()
        .GET(
            "/filtered/{id}",
            req -> ServerResponse.ok().bodyValue("filtered " + req.pathVariable("id")))
        .filter((req, next) -> next.handle(req))
        .build();
  }
}
