package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponseFor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;

public final class HttpResponseWrapper<T> implements HttpResponseFor<T> {

  public static <T> HttpResponseFor<T> wrap(
      HttpResponseFor<T> response, AgentSpan span, BiConsumer<AgentSpan, T> afterParse) {
    DECORATE.withHttpResponse(span, response.headers());
    return new HttpResponseWrapper<>(response, span, afterParse);
  }

  public static <T> CompletableFuture<HttpResponseFor<T>> wrapFuture(
      CompletableFuture<HttpResponseFor<T>> future,
      AgentSpan span,
      BiConsumer<AgentSpan, T> afterParse) {
    return future
        .thenApply(response -> wrap(response, span, afterParse))
        .whenComplete(
            (r, t) -> {
              try {
                if (t != null) {
                  DECORATE.onError(span, t);
                }
                DECORATE.beforeFinish(span);
              } finally {
                span.finish();
              }
            });
  }

  private final HttpResponseFor<T> delegate;
  private final AgentSpan span;
  private final BiConsumer<AgentSpan, T> afterParse;

  private HttpResponseWrapper(
      HttpResponseFor<T> delegate, AgentSpan span, BiConsumer<AgentSpan, T> afterParse) {
    this.delegate = delegate;
    this.span = span;
    this.afterParse = afterParse;
  }

  @Override
  public T parse() {
    try {
      T parsed = delegate.parse();
      afterParse.accept(span, parsed);
      return parsed;
    } catch (Throwable err) {
      DECORATE.onError(span, err);
      throw err;
    }
  }

  @Override
  public int statusCode() {
    return delegate.statusCode();
  }

  @NotNull
  @Override
  public Headers headers() {
    return delegate.headers();
  }

  @NotNull
  @Override
  public InputStream body() {
    return delegate.body();
  }

  @Override
  public void close() {
    // span finished after the response is available
    delegate.close();
  }
}
