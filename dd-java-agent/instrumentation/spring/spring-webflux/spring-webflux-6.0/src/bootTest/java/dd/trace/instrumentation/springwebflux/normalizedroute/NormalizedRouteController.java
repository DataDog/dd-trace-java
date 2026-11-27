package dd.trace.instrumentation.springwebflux.normalizedroute;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Annotated routes of {@link NormalizedRouteApplication}. */
@RestController
public class NormalizedRouteController {

  @GetMapping("/regex/{id:\\d+}")
  public Mono<String> regex() {
    return Mono.just("ok");
  }

  @GetMapping("/static/**")
  public Mono<String> trailingDoubleStar() {
    return Mono.just("ok");
  }

  @GetMapping("/mixed/{id}/**")
  public Mono<String> namedAndDoubleStar() {
    return Mono.just("ok");
  }

  @GetMapping("/star/*/**")
  public Mono<String> starAndDoubleStar() {
    return Mono.just("ok");
  }

  @GetMapping("/rest/{*rest}")
  public Mono<String> captureRest() {
    return Mono.just("ok");
  }
}
