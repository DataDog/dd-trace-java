package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator.suppressNestedClientSpans;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.HTTP_REQUEST;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
import org.reactivestreams.Subscription;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;

/**
 * Based on Spring Sleuth's Reactor instrumentation.
 * https://github.com/spring-cloud/spring-cloud-sleuth/blob/master/spring-cloud-sleuth-core/src/main/java/org/springframework/cloud/sleuth/instrument/web/client/TraceWebClientBeanPostProcessor.java
 */
public class WebClientTracingFilter implements ExchangeFilterFunction {
  private static final boolean OTEL_SEMANTICS = Config.get().isTraceOtelSemanticsEnabled();
  private static final ContextKey<AgentSpan> WEBCLIENT_SPAN =
      ContextKey.named("spring-webclient-span");
  private static final ContextKey<Context> WEBCLIENT_PARENT =
      ContextKey.named("spring-webclient-parent");
  private static final WebClientTracingFilter INSTANCE = new WebClientTracingFilter();
  private static final ExchangeFilterFunction TRANSPORT_FILTER =
      (request, next) -> new MonoWebClientTransport(request, next);

  /**
   * It's not expected that this method would be called concurrently. This should only be created
   * during the start-up and configuration phase of an app. If a builder is being modified on
   * multiple threads it would exhibit ConcurrentModificationException even without the agent
   */
  public static void addFilter(final List<ExchangeFilterFunction> exchangeFilterFunctions) {
    // Since the builder we instrument the can be reused, we need to only add the filter once
    exchangeFilterFunctions.removeIf(filterFunction -> filterFunction == INSTANCE);
    exchangeFilterFunctions.add(0, INSTANCE);
    if (OTEL_SEMANTICS) {
      exchangeFilterFunctions.removeIf(filterFunction -> filterFunction == TRANSPORT_FILTER);
      exchangeFilterFunctions.add(TRANSPORT_FILTER);
    }
  }

  @Override
  public Mono<ClientResponse> filter(final ClientRequest request, final ExchangeFunction next) {
    return new MonoWebClientTrace(request, next);
  }

  public static final class MonoWebClientTrace extends Mono<ClientResponse> {
    final ExchangeFunction next;
    final ClientRequest request;

    public MonoWebClientTrace(final ClientRequest request, final ExchangeFunction next) {
      this.next = next;
      this.request = request;
    }

    @Override
    public void subscribe(final CoreSubscriber<? super ClientResponse> subscriber) {
      AgentSpan span = startSpan("spring-webflux-client", HTTP_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      AgentSpan parent = activeSpan();
      Context parentContext = Context.current();
      try (final ContextScope scope = activateSpan(span);
          final ContextScope ownerScope =
              OTEL_SEMANTICS
                  ? Context.current()
                      .with(WEBCLIENT_SPAN, span)
                      .with(WEBCLIENT_PARENT, parentContext)
                      .attach()
                  : null) {
        TraceWebClientSubscriber tracingSubscriber =
            new TraceWebClientSubscriber(subscriber, span, parent);
        next.exchange(request).doOnCancel(tracingSubscriber::onCancel).subscribe(tracingSubscriber);
      }
    }
  }

  /** Applies transport suppression after the application's filters have run. */
  public static final class MonoWebClientTransport extends Mono<ClientResponse> {
    private final ClientRequest request;
    private final ExchangeFunction next;

    public MonoWebClientTransport(ClientRequest request, ExchangeFunction next) {
      this.request = request;
      this.next = next;
    }

    @Override
    public void subscribe(CoreSubscriber<? super ClientResponse> subscriber) {
      Context context = Context.current();
      AgentSpan owner = context.get(WEBCLIENT_SPAN);
      if (owner == null || owner != activeSpan()) {
        next.exchange(request).subscribe(subscriber);
        return;
      }
      try (ContextScope suppression =
          suppressNestedClientSpans(owner, context.get(WEBCLIENT_PARENT))) {
        next.exchange(request)
            .subscribe(new TransportSubscriber(subscriber, context, Context.current()));
      }
    }
  }

  /**
   * Keeps connector demand in its transport context and filter callbacks in their owner context.
   */
  public static final class TransportSubscriber
      implements CoreSubscriber<ClientResponse>, Subscription {
    private final CoreSubscriber<? super ClientResponse> actual;
    private final Context ownerContext;
    private final Context transportContext;
    private Subscription subscription;

    public TransportSubscriber(
        CoreSubscriber<? super ClientResponse> actual,
        Context ownerContext,
        Context transportContext) {
      this.actual = actual;
      this.ownerContext = ownerContext;
      this.transportContext = transportContext;
    }

    @Override
    public reactor.util.context.Context currentContext() {
      return actual.currentContext();
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      this.subscription = subscription;
      try (ContextScope scope = ownerContext.attach()) {
        actual.onSubscribe(this);
      }
    }

    @Override
    public void request(long count) {
      try (ContextScope scope = transportContext.attach()) {
        subscription.request(count);
      }
    }

    @Override
    public void cancel() {
      try (ContextScope scope = transportContext.attach()) {
        subscription.cancel();
      }
    }

    @Override
    public void onNext(ClientResponse response) {
      try (ContextScope scope = ownerContext.attach()) {
        actual.onNext(response);
      }
    }

    @Override
    public void onError(Throwable error) {
      try (ContextScope scope = ownerContext.attach()) {
        actual.onError(error);
      }
    }

    @Override
    public void onComplete() {
      try (ContextScope scope = ownerContext.attach()) {
        actual.onComplete();
      }
    }
  }
}
