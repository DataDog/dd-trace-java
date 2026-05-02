package datadog.trace.instrumentation.springwebflux.server;

import datadog.context.Context;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class HandleResultAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void methodExit(
      @Advice.Argument(0) ServerWebExchange exchange,
      @Advice.Return(readOnly = false) Mono<Void> mono) {
    final AgentSpan span = exchange.getAttribute(AdviceUtils.SPAN_ATTRIBUTE);
    if (span != null && mono != null) {
      InstrumentationContext.get(Publisher.class, Context.class).put(mono, span);
    }
  }
}
