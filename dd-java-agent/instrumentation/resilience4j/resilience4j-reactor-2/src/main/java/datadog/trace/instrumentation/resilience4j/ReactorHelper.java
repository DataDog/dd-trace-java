package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

public class ReactorHelper {

  public static Consumer<SignalType> beforeFinish(AgentSpan span) {
    return signalType -> span.finish();
  }

  public static Function<Publisher<?>, Publisher<?>> wrapFunction(
      Function<Publisher<?>, Publisher<?>> operator,
      BiConsumer<Publisher<?>, AgentSpan> attachContext) {
    return (value) -> {
      AgentSpan span = startSpan("fallback");
      try (AgentScope scope = activateSpan(span)) {
        Publisher<?> ret = operator.apply(value);
        attachContext.accept(ret, span);
        if (ret instanceof Flux<?>) {
          return ((Flux<?>) ret).doFinally(beforeFinish(span));
        } else { // TODO
          // can't schedule finish
          scope.span().finish();
        }
        return ret;
      }
    };
  }
}
