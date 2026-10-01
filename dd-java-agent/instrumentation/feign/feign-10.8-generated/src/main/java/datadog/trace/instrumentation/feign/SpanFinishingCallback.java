package datadog.trace.instrumentation.feign;

import static datadog.trace.instrumentation.feign.FeignClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import feign.Response;
import java.util.function.BiConsumer;

/** Callback to finish a span when a {@link java.util.concurrent.CompletableFuture} completes. */
public class SpanFinishingCallback implements BiConsumer<Response, Throwable> {

  private final AgentSpan span;

  public SpanFinishingCallback(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public void accept(final Response response, final Throwable error) {
    try {
      if (response != null) {
        DECORATE.onResponse(span, response);
      }
      if (error != null) {
        DECORATE.onError(span, error);
      }
      DECORATE.beforeFinish(span);
    } finally {
      span.finish();
    }
  }
}
