package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;

public final class HttpStreamResponseWrapper<T> implements HttpResponseFor<StreamResponse<T>> {

  public static <T> HttpResponseFor<StreamResponse<T>> wrap(
      HttpResponseFor<StreamResponse<T>> response,
      final AgentSpan span,
      BiConsumer<AgentSpan, List<T>> decorate) {
    DECORATE.withHttpResponse(span, response.headers());
    return new HttpStreamResponseWrapper<>(response, span, decorate);
  }

  public static <T> CompletableFuture<HttpResponseFor<StreamResponse<T>>> wrapFuture(
      CompletableFuture<HttpResponseFor<StreamResponse<T>>> future,
      AgentSpan span,
      BiConsumer<AgentSpan, List<T>> decorate) {
    return future
        .thenApply(r -> wrap(r, span, decorate))
        .whenComplete(
            (_r, err) -> {
              if (err != null) {
                DECORATE.finishSpan(span, err);
              }
            });
  }

  private final HttpResponseFor<StreamResponse<T>> delegate;
  private final AgentSpan span;
  private final BiConsumer<AgentSpan, List<T>> decorate;
  private final AtomicBoolean parseCalled = new AtomicBoolean(false);

  private HttpStreamResponseWrapper(
      HttpResponseFor<StreamResponse<T>> delegate,
      AgentSpan span,
      BiConsumer<AgentSpan, List<T>> decorate) {
    this.delegate = delegate;
    this.span = span;
    this.decorate = decorate;
  }

  @Override
  public StreamResponse<T> parse() {
    try {
      StreamResponse<T> parsed = delegate.parse();
      return new HttpStreamResponseStreamWrapper<>(span, decorate, parsed);
    } catch (Throwable err) {
      DECORATE.finishSpan(span, err);
      throw err;
    } finally {
      parseCalled.set(true);
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
    if (parseCalled.compareAndSet(false, true)) {
      DECORATE.finishSpan(span, null);
    }
    delegate.close();
  }
}
