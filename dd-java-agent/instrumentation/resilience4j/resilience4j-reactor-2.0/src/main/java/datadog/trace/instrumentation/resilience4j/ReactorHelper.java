package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

public class ReactorHelper {

  private static final Logger log = LoggerFactory.getLogger(ReactorHelper.class);

  public static Function<Publisher<?>, Publisher<?>> wrapFunction(
      Function<Publisher<?>, Publisher<?>> operator,
      BiConsumer<Publisher<?>, AgentSpan> attachContext) {
    return (value) -> {
      AgentSpan current = Resilience4jSpan.current();
      AgentSpan owned = current == null ? Resilience4jSpan.start() : null;
      Resilience4jSpanDecorator<Void> spanDecorator = Resilience4jSpanDecorator.DECORATE;
      if (owned != null) {
        current = owned;
        spanDecorator.afterStart(current);
      }
      spanDecorator.decorate(current, null);
      try (AgentScope scope = activateSpan(current)) {
        Publisher<?> ret = operator.apply(value);
        attachContext.accept(ret, current);
        if (owned == null) {
          return ret;
        }
        return scheduleOwnedSpanFinish(ret, spanDecorator, owned);
      }
    };
  }

  public static <T> Publisher<?> wrapPublisher(
      Publisher<?> publisher,
      Resilience4jSpanDecorator<T> spanDecorator,
      T data,
      BiConsumer<Publisher<?>, AgentSpan> attachContext) {
    // Create span at construction (needs transformDeferred which is what Spring R4j use)
    AgentSpan current = Resilience4jSpan.current();
    AgentSpan owned = current == null ? Resilience4jSpan.start() : null;
    if (owned != null) {
      current = owned;
      spanDecorator.afterStart(current);
    }
    spanDecorator.decorate(current, data);

    // This schedules a span to be finished when the publisher finishes to be non-zero
    Publisher<?> newResult = scheduleOwnedSpanFinish(publisher, spanDecorator, owned);
    if (newResult instanceof Scannable) {
      Scannable parent = (Scannable) newResult;
      while (parent != null) {
        if (parent instanceof Publisher) {
          // Attach the span to the publisher to be activated by the reactive streams
          // instrumentation to scope child spans
          attachContext.accept((Publisher<?>) parent, current);
        }
        parent = parent.scan(Scannable.Attr.PARENT);
      }
    }
    return newResult;
  }

  private static <T> Publisher<?> scheduleOwnedSpanFinish(
      Publisher<?> publisher, Resilience4jSpanDecorator<T> spanDecorator, AgentSpan owned) {
    if (owned == null) {
      return publisher;
    }
    if (publisher instanceof Flux<?>) {
      return ((Flux<?>) publisher).doFinally(beforeFinish(spanDecorator, owned));
    } else if (publisher instanceof Mono<?>) {
      return ((Mono<?>) publisher).doFinally(beforeFinish(spanDecorator, owned));
    } else {
      log.debug("Unexpected type of publisher {}", publisher);
      // can't schedule span finish - finish immediately
      spanDecorator.beforeFinish(owned);
      owned.finish();
    }
    return publisher;
  }

  private static <T> Consumer<SignalType> beforeFinish(
      Resilience4jSpanDecorator<T> spanDecorator, AgentSpan span) {
    return signalType -> {
      spanDecorator.beforeFinish(span);
      span.finish();
    };
  }
}
