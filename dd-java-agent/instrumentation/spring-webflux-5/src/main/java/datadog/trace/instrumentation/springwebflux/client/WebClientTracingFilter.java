package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.HTTP_REQUEST;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;
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
  private static final WebClientTracingFilter INSTANCE = new WebClientTracingFilter();

  /**
   * It's not expected that this method would be called concurrently. This should only be created
   * during the start-up and configuration phase of an app. If a builder is being modified on
   * multiple threads it would exhibit ConcurrentModificationException even without the agent
   */
  public static void addFilter(final List<ExchangeFilterFunction> exchangeFilterFunctions) {
    // Since the builder we instrument the can be reused, we need to only add the filter once
    exchangeFilterFunctions.removeIf(filterFunction -> filterFunction == INSTANCE);
    exchangeFilterFunctions.add(0, INSTANCE);
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
      AgentSpan span = startSpan(HTTP_REQUEST);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      AgentSpan parent = activeSpan();
      try (final AgentScope scope = activateSpan(span)) {
        TraceWebClientSubscriber tracingSubscriber =
            new TraceWebClientSubscriber(subscriber, span, parent);
        next.exchange(request).doOnCancel(tracingSubscriber::onCancel).subscribe(tracingSubscriber);
      }
    }
  }
}
