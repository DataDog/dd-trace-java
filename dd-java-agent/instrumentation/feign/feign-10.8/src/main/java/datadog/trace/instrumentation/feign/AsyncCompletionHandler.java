package datadog.trace.instrumentation.feign;

import static datadog.trace.instrumentation.feign.FeignClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import feign.Response;
import java.util.function.BiConsumer;

public class AsyncCompletionHandler implements BiConsumer<Response, Throwable> {
  private final AgentSpan span;

  public AsyncCompletionHandler(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void accept(Response response, Throwable error) {
    try {
      if (error != null) {
        DECORATE.onError(span, error);
      } else {
        DECORATE.onResponse(span, response);
      }
      DECORATE.beforeFinish(span);
    } finally {
      span.finish();
    }
  }
}
