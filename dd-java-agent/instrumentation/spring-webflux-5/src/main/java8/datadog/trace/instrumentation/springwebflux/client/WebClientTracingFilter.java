package datadog.trace.instrumentation.springwebflux.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springwebflux.client.HttpHeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.springwebflux.client.SpringWebfluxHttpClientDecorator.HTTP_REQUEST;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.List;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

public class WebClientTracingFilter implements ExchangeFilterFunction {
  private static final WebClientTracingFilter INSTANCE = new WebClientTracingFilter();

  public static void addFilter(final List<ExchangeFilterFunction> exchangeFilterFunctions) {
    // Since the builder where we instrument the build function can be reused, we need
    // to only add the filter once
    int index = exchangeFilterFunctions.indexOf(INSTANCE);
    if (index == 0) {
      return;
    }
    if (index > 0) {
      exchangeFilterFunctions.remove(index);
    }
    exchangeFilterFunctions.add(0, INSTANCE);
  }

  @Override
  public Mono<ClientResponse> filter(final ClientRequest request, final ExchangeFunction next) {
    final AgentSpan span;
    if (activeSpan() != null) {
      span = startSpan(HTTP_REQUEST, activeSpan().context());
    } else {
      span = startSpan(HTTP_REQUEST);
    }
    span.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    span.setTag(InstrumentationTags.DD_MEASURED, true);
    DECORATE.afterStart(span);

    try (final AgentScope scope = activateSpan(span)) {
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
                } else {
                  DECORATE.onResponse(span, clientResponse);
                }
                DECORATE.beforeFinish(span);
                span.finish();
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
