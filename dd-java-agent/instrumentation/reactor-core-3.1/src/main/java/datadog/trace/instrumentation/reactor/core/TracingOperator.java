package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import reactor.core.Fuseable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;

/** Based on Spring Sleuth's Reactor instrumentation. */
public class TracingOperator extends ClassValue<Boolean> {

  private static final TracingOperator FILTER = new TracingOperator();

  @Override
  protected Boolean computeValue(final Class<?> type) {
    return type.getName().startsWith("reactor.core.Scannable$Attr$");
  }

  /**
   * Registers a hook that applies to every operator, propagating {@link Context} to downstream
   * callbacks to ensure spans in the {@link Context} are available throughout the lifetime of a
   * reactive stream. This should generally be called in a static initializer block in your
   * application.
   */
  public static void registerOnEachOperator() {
    Hooks.onEachOperator(TracingSubscriber.class.getName(), tracingLift());
  }

  private static <T> Function<? super Publisher<T>, ? extends Publisher<T>> tracingLift() {
    return Operators.lift(
        (publisher, subscriber) -> {
          AgentSpan span = activeSpan();
          return null == span
                  || publisher instanceof Fuseable.ScalarCallable
                  || subscriber instanceof TracingSubscriber
                  || FILTER.get(publisher.getClass())
              ? subscriber
              : new TracingSubscriber<>(subscriber, subscriber.currentContext(), span);
        });
  }
}
