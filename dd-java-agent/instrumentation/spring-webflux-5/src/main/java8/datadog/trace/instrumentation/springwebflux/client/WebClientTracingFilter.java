package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springwebflux.client.HttpHeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

public class WebClientTracingFilter implements ExchangeFilterFunction {
  @Override
  public Mono<ClientResponse> filter(final ClientRequest request, final ExchangeFunction next) {
    final AgentSpan span;
    if (activeSpan() != null) {
      span = startSpan("http.request", activeSpan().context());
    } else {
      span = startSpan("http.request");
    }
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    DECORATE.afterStart(span);

    try (final AgentScope scope = activateSpan(span, false)) {
      scope.setAsyncPropagation(true);
      final ClientRequest mutatedRequest =
          ClientRequest.from(request)
              .attribute(AgentSpan.class.getName(), span)
              .headers(httpHeaders -> propagate().inject(span, httpHeaders, SETTER))
              .build();
      DECORATE.onRequest(span, mutatedRequest);

      return next.exchange(mutatedRequest)
          .doOnSuccessOrError(
              (clientResponse, throwable) -> {
                if (throwable != null) {
                  DECORATE.onError(span, throwable);
                  DECORATE.beforeFinish(span);
                  span.finish();
                } else {
                  DECORATE.onResponse(span, clientResponse);
                  DECORATE.beforeFinish(span);
                  span.finish();
                }
              })
          .doOnCancel(
              () -> {
                DECORATE.onCancel(span);
                DECORATE.beforeFinish(span);
                span.finish();
              });
    }
  }
}
