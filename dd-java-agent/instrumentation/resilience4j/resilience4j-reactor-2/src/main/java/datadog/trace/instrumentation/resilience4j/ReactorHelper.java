package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

public class ReactorHelper {

  private static Consumer<SignalType> beforeFinish(AgentSpan span) {
    return signalType -> {
      span.finish();
    };
  }

  public static Function<Publisher<?>, Publisher<?>> wrapFunction(
      Function<Publisher<?>, Publisher<?>> operator,
      BiConsumer<Publisher<?>, AgentSpan> attachContext) {
    return (value) -> {
      AgentSpan current = ActiveResilience4jSpan.current();
      AgentSpan owned = current == null ? ActiveResilience4jSpan.start() : null;
      if (owned != null) {
        current = owned;
        NoopDecorator.DECORATE.afterStart(current);
      }
      try (AgentScope scope = activateSpan(current)) {
        Publisher<?> ret = operator.apply(value);
        attachContext.accept(ret, current);
        if (owned == null) {
          return ret;
        }
        if (ret instanceof Flux<?>) {
          return ((Flux<?>) ret).doFinally(beforeFinish(owned));
        } else if (ret instanceof Mono<?>) {
          return ((Mono<?>) ret).doFinally(beforeFinish(owned));
        } else {
          // can't schedule span finish - finish immediately
          owned.finish();
        }
        return ret;
      }
    };
  }

  public static <T> Object wrap(
      Object result,
      AbstractResilience4jDecorator<T> spanDecorator,
      T data,
      BiConsumer<Publisher<?>, AgentSpan> attachContext) {
    AgentSpan current = ActiveResilience4jSpan.current();
    AgentSpan owned = current == null ? ActiveResilience4jSpan.start() : null;
    if (owned != null) {
      current = owned;
      spanDecorator.afterStart(current);
    }
    spanDecorator.decorate(current, data);

    Object newResult = result;
    if (result instanceof Flux<?>) {
      newResult = ((Flux<?>) result).doFinally(beforeFinish(owned));
    } else if (result instanceof Mono<?>) {
      newResult = ((Mono<?>) result).doFinally(beforeFinish(owned));
    } else {
      // can't schedule span finish - finish immediately
      owned.finish();
    }
    if (newResult instanceof Scannable) {
      Scannable parent = (Scannable) newResult;
      while (parent != null) {
        if (parent instanceof Publisher) {
          attachContext.accept((Publisher) parent, current);
        }
        parent = parent.scan(Scannable.Attr.PARENT);
      }
    }
    return newResult;
  }
}
