package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.instrumentation.api.AgentTracer.activeScope;

import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.netty41.AttributeKeys;
import java.util.function.Function;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientRequest;

public class ReactorHttpClientAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void methodEnter(
      @Advice.This final HttpClient thiz,
      @Advice.Argument(value = 2, readOnly = false)
          Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
    handler = handler(handler);
  }

  public static Function<? super HttpClientRequest, ? extends Publisher<Void>> handler(
      final Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
    final TraceScope scope = activeScope();
    if (scope == null) {
      return handler;
    }
    return (req) -> {
      req.context(
          ctx ->
              ctx.channel()
                  .attr(AttributeKeys.PARENT_CONNECT_CONTINUATION_ATTRIBUTE_KEY)
                  .set(scope.capture()));
      return handler.apply(req);
    };
  }
}
