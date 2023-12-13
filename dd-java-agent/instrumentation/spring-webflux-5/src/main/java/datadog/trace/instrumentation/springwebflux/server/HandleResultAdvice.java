package datadog.trace.instrumentation.springwebflux.server;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public class HandleResultAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void methodExit(
      @Advice.Argument(0) ServerWebExchange exchange,
      @Advice.Return(readOnly = false) Mono<Void> mono) {
    final AgentSpan span = exchange.getAttribute(AdviceUtils.SPAN_ATTRIBUTE);
    if (span != null && mono != null) {
      mono = AdviceUtils.wrapMonoWithScope(mono, span);
    }
  }
}
