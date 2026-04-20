package datadog.trace.instrumentation.reactor.netty;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

public class CaptureConnectSpan
    implements Function<Mono<? extends Connection>, Mono<? extends Connection>> {

  static final String CONNECT_SPAN = "datadog.connect.span";

  @Override
  public Mono<? extends Connection> apply(Mono<? extends Connection> mono) {
    return mono.contextWrite(
        context -> {
          final AgentSpan span = activeSpan();
          if (null != span) {
            return context.put(CONNECT_SPAN, span);
          } else {
            return context;
          }
        });
  }
}
