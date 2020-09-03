package datadog.trace.instrumentation.reactor.core;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Fuseable;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.GroupedFlux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.ParallelFlux;

@Slf4j
public class TracingPublishers {

  /**
   * Instead of using {@link reactor.core.publisher.Operators#lift} (available in reactor 3.1) or
   * {@link reactor.core.publisher.Operators#liftPublisher} (available in reactor 3.3) we create our
   * own version of {@link reactor.core.publisher.Operators#liftPublisher} that allows us to run
   * code at assembly time. The built in reactor functions handle all of the assembly time actions
   * internally so we are unable to attach spans to a Publisher context at the points where it would
   * make sense for us.
   *
   * <p>By doing this ourselves we will want to keep this in line with the {@link
   * reactor.core.publisher.Operators.LiftFunction} implementation in order to ensure greatest
   * compatibility
   */
  public static <T> Publisher<T> wrap(final Publisher<T> delegate) {
    AgentSpan span = activeSpan();
    if (span == null) {
      span = noopSpan();
    }

    // based on Operators.LiftFunction.apply in reactor 3.3.4
    if (delegate instanceof Fuseable) {
      if (delegate instanceof Mono) {
        return new FuseableMonoTracingPublisher<>(span, (Mono<T>) delegate);
      }
      if (delegate instanceof ParallelFlux) {
        return new FuseableParallelFluxTracingPublisher<>(span, (ParallelFlux<T>) delegate);
      }
      if (delegate instanceof ConnectableFlux) {
        return new FuseableConnectableFluxTracingPublisher<>(span, (ConnectableFlux<T>) delegate);
      }
      if (delegate instanceof GroupedFlux) {
        return new FuseableGroupedFluxTracingPublisher<>(span, (GroupedFlux<?, T>) delegate);
      }
      return new FuseableFluxTracingPublisher<>(span, (Flux<T>) delegate);
    } else {
      if (delegate instanceof Mono) {
        return new MonoTracingPublisher<>(span, (Mono<T>) delegate);
      }
      if (delegate instanceof ParallelFlux) {
        return new ParallelFluxTracingPublisher<>(span, (ParallelFlux<T>) delegate);
      }
      if (delegate instanceof ConnectableFlux) {
        return new ConnectableFluxTracingPublisher<>(span, (ConnectableFlux<T>) delegate);
      }
      if (delegate instanceof GroupedFlux) {
        return new GroupedFluxTracingPublisher<>(span, (GroupedFlux<?, T>) delegate);
      }
      return new FluxTracingPublisher<>(span, (Flux<T>) delegate);
    }
  }

  static <T> CoreSubscriber<? super T> wrapSubscriber(
      final AgentSpan span, final CoreSubscriber<? super T> actual) {
    if (actual instanceof TracingSubscriber) {
      return actual;
    } else {
      return new TracingSubscriber<>(span, actual);
    }
  }

  public static class MonoTracingPublisher<T> extends Mono<T> {
    private final AgentSpan span;
    private final Mono<T> delegate;

    public MonoTracingPublisher(final AgentSpan span, final Mono<T> delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public void subscribe(final CoreSubscriber<? super T> actual) {
      try (final AgentScope scope = activateSpan(span)) {
        scope.setAsyncPropagation(true);
        delegate.subscribe(wrapSubscriber(span, actual));
      }
    }
  }

  public static class ParallelFluxTracingPublisher<T> extends ParallelFlux<T> {
    private final AgentSpan span;
    private final ParallelFlux<T> delegate;

    public ParallelFluxTracingPublisher(final AgentSpan span, final ParallelFlux<T> delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public int parallelism() {
      return delegate.parallelism();
    }

    @Override
    protected void subscribe(final CoreSubscriber<? super T>[] subscribers) {
      try (final AgentScope scope = activateSpan(span)) {
        scope.setAsyncPropagation(true);
        for (final CoreSubscriber<? super T> subscriber : subscribers) {
          delegate.subscribe(wrapSubscriber(span, subscriber));
        }
      }
    }
  }

  public static class ConnectableFluxTracingPublisher<T> extends ConnectableFlux<T> {
    private final AgentSpan span;
    private final ConnectableFlux<T> delegate;

    public ConnectableFluxTracingPublisher(
        final AgentSpan span, final ConnectableFlux<T> delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public void connect(final Consumer<? super Disposable> cancelSupport) {
      try (final AgentScope scope = activateSpan(span)) {
        delegate.connect(cancelSupport);
      }
    }

    @Override
    public void subscribe(final CoreSubscriber<? super T> actual) {
      try (final AgentScope scope = activateSpan(span)) {
        scope.setAsyncPropagation(true);
        delegate.subscribe(wrapSubscriber(span, actual));
      }
    }
  }

  public static class GroupedFluxTracingPublisher<O, T> extends GroupedFlux<O, T> {
    private final AgentSpan span;
    private final GroupedFlux<O, T> delegate;

    public GroupedFluxTracingPublisher(final AgentSpan span, final GroupedFlux<O, T> delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public O key() {
      return delegate.key();
    }

    @Override
    public void subscribe(final CoreSubscriber<? super T> actual) {
      try (final AgentScope scope = activateSpan(span)) {
        scope.setAsyncPropagation(true);
        delegate.subscribe(wrapSubscriber(span, actual));
      }
    }
  }

  public static class FluxTracingPublisher<T> extends Flux<T> {
    private final AgentSpan span;
    private final Flux<T> delegate;

    public FluxTracingPublisher(final AgentSpan span, final Flux<T> delegate) {
      this.span = span;
      this.delegate = delegate;
    }

    @Override
    public void subscribe(final CoreSubscriber<? super T> actual) {
      try (final AgentScope scope = activateSpan(span)) {
        scope.setAsyncPropagation(true);
        delegate.subscribe(wrapSubscriber(span, actual));
      }
    }
  }

  public static class FuseableMonoTracingPublisher<T> extends MonoTracingPublisher<T>
      implements Fuseable {
    public FuseableMonoTracingPublisher(final AgentSpan span, final Mono<T> delegate) {
      super(span, delegate);
    }
  }

  public static class FuseableParallelFluxTracingPublisher<T>
      extends ParallelFluxTracingPublisher<T> implements Fuseable {
    public FuseableParallelFluxTracingPublisher(
        final AgentSpan span, final ParallelFlux<T> delegate) {
      super(span, delegate);
    }
  }

  public static class FuseableConnectableFluxTracingPublisher<T>
      extends ConnectableFluxTracingPublisher<T> implements Fuseable {
    public FuseableConnectableFluxTracingPublisher(
        final AgentSpan span, final ConnectableFlux<T> delegate) {
      super(span, delegate);
    }
  }

  public static class FuseableGroupedFluxTracingPublisher<O, T>
      extends GroupedFluxTracingPublisher<O, T> implements Fuseable {
    public FuseableGroupedFluxTracingPublisher(
        final AgentSpan span, final GroupedFlux<O, T> delegate) {
      super(span, delegate);
    }
  }

  public static class FuseableFluxTracingPublisher<T> extends FluxTracingPublisher<T>
      implements Fuseable {
    public FuseableFluxTracingPublisher(final AgentSpan span, final Flux<T> delegate) {
      super(span, delegate);
    }
  }
}
