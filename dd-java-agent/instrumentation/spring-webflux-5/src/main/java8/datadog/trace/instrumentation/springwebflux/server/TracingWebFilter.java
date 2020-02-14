package datadog.trace.instrumentation.springwebflux.server;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
public class TracingWebFilter implements WebFilter {
  @Override
  public Mono<Void> filter(final ServerWebExchange exchange, final WebFilterChain chain) {
    log.debug("HERE");
    return chain.filter(exchange).doOnSuccessOrError((v, t) -> log.debug("HERE"));
  }
}
