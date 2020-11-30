package datadog.trace.instrumentation.reactor.core;

import java.util.function.BiFunction;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.Scannable;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Operators;

/** Based on Spring Sleuth's Reactor instrumentation. */
public class TracingOperator {

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
    return Operators.lift(new Lifter<>());
  }

  public static class Lifter<T>
      implements BiFunction<Scannable, CoreSubscriber<? super T>, CoreSubscriber<? super T>> {

    private static final LifterFilter filter = new LifterFilter();

    @Override
    public CoreSubscriber<? super T> apply(
        final Scannable publisher, final CoreSubscriber<? super T> sub) {
      // if Flux/Mono #just, #empty, #error
      if (filter.get(publisher.getClass())) {
        return sub;
      }
      return new TracingSubscriber<>(sub, sub.currentContext());
    }
  }

  public static class LifterFilter extends ClassValue<Boolean> {
    @Override
    protected Boolean computeValue(final Class<?> type) {
      return Fuseable.ScalarCallable.class.isAssignableFrom(type)
          || type.getName().startsWith("reactor.core.Scannable$Attr$");
    }
  }
}
