package dd.trace.instrumentation.springwebflux.normalizedroute;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Minimal app with the route shapes that need presence logic in the normalized route. */
@SpringBootApplication
public class NormalizedRouteApplication {

  @Bean
  RouterFunction<ServerResponse> functionalRestRoute() {
    return route(GET("/fn/{*rest}"), request -> ServerResponse.ok().bodyValue("ok"));
  }

  @Bean
  RouterFunction<ServerResponse> functionalDoubleStarRoute() {
    return route(GET("/fns/**"), request -> ServerResponse.ok().bodyValue("ok"));
  }
}
