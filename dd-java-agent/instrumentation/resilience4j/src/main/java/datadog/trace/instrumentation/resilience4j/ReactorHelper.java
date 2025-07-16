package datadog.trace.instrumentation.resilience4j;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

public class ReactorHelper {

  public static <T> Flux<?> wrapFlux(
      Flux<?> result, AbstractResilience4jDecorator<T> spanDecorator, T data) {

    AgentSpan activeSpan = ActiveResilience4jSpan.current();
    if (activeSpan != null) {
      spanDecorator.decorate(activeSpan, data);
      // TODO should it be explicitly kept in the context to make sure it's propagated downstream or
      // if it's already active it will be propagated anyways?
      //  return result.contextWrite(Context.of("dd.span", activeSpan));
      return result;
    }
    AgentSpan span = ActiveResilience4jSpan.start();
    spanDecorator.decorate(span, data);
    // pass span to the reactor instrumentation to be activated in the downstream
    return result
        .contextWrite(Context.of("dd.span", span))
        .doFinally(
            signalType -> {
              // TODO handle error? doOnError?
              span.finish();
            });
  }
}
