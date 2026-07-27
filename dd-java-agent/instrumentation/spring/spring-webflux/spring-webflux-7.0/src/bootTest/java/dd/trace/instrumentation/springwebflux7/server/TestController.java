package dd.trace.instrumentation.springwebflux7.server;

import datadog.trace.api.Trace;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

@RestController
public class TestController {

  @GetMapping("/foo")
  public Mono<FooModel> getFooModel() {
    return Mono.just(new FooModel(0L, "DEFAULT"));
  }

  @GetMapping("/foo/{id}")
  public Mono<FooModel> getFooModel(@PathVariable("id") long id) {
    return Mono.just(new FooModel(id, "pass"));
  }

  @GetMapping("/foo/{id}/{name}")
  public Mono<FooModel> getFooModel(
      @PathVariable("id") long id, @PathVariable("name") String name) {
    return Mono.just(new FooModel(id, name));
  }

  @GetMapping("/foo-delayed")
  public Mono<FooModel> getFooDelayed() {
    return Mono.just(new FooModel(3L, "delayed")).delayElement(Duration.ofMillis(100));
  }

  @GetMapping("/foo-failfast/{id}")
  public Mono<FooModel> getFooFailFast(@PathVariable("id") long id) {
    throw new RuntimeException("bad things happen");
  }

  @GetMapping("/foo-failmono/{id}")
  public Mono<FooModel> getFooFailMono(@PathVariable("id") long id) {
    return Mono.error(new RuntimeException("bad things happen"));
  }

  @GetMapping("/foo-traced-method/{id}")
  public Mono<FooModel> getTracedMethod(@PathVariable("id") long id) {
    return Mono.just(tracedMethod(id));
  }

  @GetMapping("/foo-mono-from-callable/{id}")
  public Mono<FooModel> getMonoFromCallable(@PathVariable("id") long id) {
    return Mono.fromCallable(() -> tracedMethod(id));
  }

  @GetMapping("/foo-delayed-mono/{id}")
  public Mono<FooModel> getFooDelayedMono(@PathVariable("id") long id) {
    return Mono.just(id).delayElement(Duration.ofMillis(100)).map(i -> tracedMethod(i));
  }

  @GetMapping("/very-delayed")
  public Mono<ServerResponse> getVeryDelayedMono() {
    return Mono.delay(Duration.ofSeconds(30)) // long enough not to finish
        .then(ServerResponse.status(200).build());
  }

  @Trace
  private FooModel tracedMethod(long id) {
    return new FooModel(id, "tracedMethod");
  }
}
