import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.ScopeSource
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import reactor.core.publisher.Mono

import java.util.function.Supplier

class SchedulerTest extends AgentTestRunner {
  class TracingSubscriber<T> implements Subscriber<T> {
    private final AgentSpan span
    private final Subscriber<T> delegate

    TracingSubscriber(Subscriber<T> delegate, AgentSpan span) {
      this.delegate = delegate
      this.span = span
    }

    @Override
    void onSubscribe(Subscription subscription) {
      delegate.onSubscribe(subscription)
    }

    @Override
    void onNext(T t) {
      try (def scope = TEST_TRACER.activateSpan(span, ScopeSource.MANUAL)) {
        delegate.onNext(t)
        span.finish()
      }
    }

    @Override
    void onError(Throwable throwable) {
      delegate.onError(throwable)
    }

    @Override
    void onComplete() {
      delegate.onComplete()
    }
  }

  class TracingPublisher<T> implements Publisher<T> {
    private final Publisher<T> delegate
    private final Supplier<AgentSpan> spanSupplier

    TracingPublisher(Publisher<T> delegate, Supplier<AgentSpan> spanSupplier) {
      this.delegate = delegate
      this.spanSupplier = spanSupplier
    }

    @Override
    void subscribe(Subscriber<? super T> subscriber) {
      delegate.subscribe(new TracingSubscriber<>(subscriber, spanSupplier.get()))
    }
  }

  def "should propagate producer context for publishers on other schedulers"() {
    when:
    Mono
    .from(new TracingPublisher<>(Mono.just("Hello World"),
    { TEST_TRACER.startSpan("test", "parent") }))
    .publishOn(new TestScheduler())
    .subscribe {
      runUnderTrace("child", {})
    }


    then:
    assertTraces(1, {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        basicSpan(it, "child", span(0))
      }
    })
  }

  def "should propagate producer context for subscribers on other schedulers"() {
    when:

    Mono
    .from(new TracingPublisher<>(Mono.just("Hello World"),
    { TEST_TRACER.startSpan("test", "parent") }))
    .subscribeOn(new TestScheduler())
    .subscribe {
      runUnderTrace("child", {})
    }


    then:
    assertTraces(1, {
      trace(2) {
        sortSpansByStart()
        basicSpan(it, "parent")
        basicSpan(it, "child", span(0))
      }
    })
  }
}
