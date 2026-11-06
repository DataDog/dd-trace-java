package datadog.trace.instrumentation.azure.cosmos;

import static datadog.trace.instrumentation.azure.cosmos.AzureCosmosDecorator.DECORATE;

import com.azure.cosmos.implementation.RxDocumentServiceResponse;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;

/**
 * Finishes the request span on the first terminal signal (response, error or cancellation), then
 * forwards signals downstream under the context that was active when the request was subscribed.
 * This keeps the SDK work that follows a request, such as a retry, from being parented to the
 * finished request span.
 */
public final class TracingCosmosSubscriber
    implements CoreSubscriber<RxDocumentServiceResponse>, Subscription {
  private static final AtomicIntegerFieldUpdater<TracingCosmosSubscriber> FINISHED =
      AtomicIntegerFieldUpdater.newUpdater(TracingCosmosSubscriber.class, "finished");

  private final CoreSubscriber<? super RxDocumentServiceResponse> actual;
  private final AgentSpan span;
  private final Context parentContext;
  private Subscription upstream;
  private volatile int finished;

  TracingCosmosSubscriber(
      CoreSubscriber<? super RxDocumentServiceResponse> actual,
      AgentSpan span,
      Context parentContext) {
    this.actual = actual;
    this.span = span;
    this.parentContext = parentContext;
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    upstream = subscription;
    actual.onSubscribe(this);
  }

  @Override
  public void onNext(RxDocumentServiceResponse response) {
    if (FINISHED.compareAndSet(this, 0, 1)) {
      try {
        DECORATE.onResponse(span, response);
      } catch (Throwable ignored) {
        // tags are best effort
      }
      finishSpan();
    }
    try (ContextScope ignored = parentContext.attach()) {
      actual.onNext(response);
    }
  }

  @Override
  public void onError(Throwable error) {
    if (FINISHED.compareAndSet(this, 0, 1)) {
      try {
        DECORATE.onError(span, error);
      } catch (Throwable ignored) {
        // tags are best effort
      }
      finishSpan();
    }
    try (ContextScope ignored = parentContext.attach()) {
      actual.onError(error);
    }
  }

  @Override
  public void onComplete() {
    if (FINISHED.compareAndSet(this, 0, 1)) {
      finishSpan();
    }
    try (ContextScope ignored = parentContext.attach()) {
      actual.onComplete();
    }
  }

  @Override
  public void request(long n) {
    upstream.request(n);
  }

  @Override
  public void cancel() {
    upstream.cancel();
    if (FINISHED.compareAndSet(this, 0, 1)) {
      finishSpan();
    }
  }

  @Override
  public reactor.util.context.Context currentContext() {
    return actual.currentContext();
  }

  private void finishSpan() {
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
