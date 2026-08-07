package datadog.trace.instrumentation.reactor.netty;

import datadog.context.Context;
import java.util.function.Function;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;

public class CaptureConnectSpan
    implements Function<Mono<? extends Connection>, Mono<? extends Connection>> {

  static final String CONNECT_CONTEXT = "datadog.connect.context";

  @Override
  public Mono<? extends Connection> apply(Mono<? extends Connection> mono) {
    return mono.contextWrite(
        reactorCtx -> {
          final Context context = Context.current();
          if (context != Context.root()) {
            return reactorCtx.put(CONNECT_CONTEXT, context);
          } else {
            return reactorCtx;
          }
        });
  }
}
