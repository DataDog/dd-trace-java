package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponseFor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpResponseWrapper<T> implements HttpResponseFor<T> {
  private static final Logger log = LoggerFactory.getLogger(HttpResponseWrapper.class);

  public static <T> HttpResponseFor<T> wrap(
      HttpResponseFor<T> response, AgentSpan span, BiConsumer<AgentSpan, T> decorate) {
    DECORATE.withHttpResponse(span, response.headers());
    return new HttpResponseWrapper<>(response, span, decorate);
  }

  public static <T> CompletableFuture<HttpResponseFor<T>> wrapFuture(
      CompletableFuture<HttpResponseFor<T>> future,
      AgentSpan span,
      BiConsumer<AgentSpan, T> decorate) {
    return future
        .thenApply(response -> wrap(response, span, decorate))
        .whenComplete((_r, t) -> DECORATE.finishSpan(span, t));
  }

  private final HttpResponseFor<T> delegate;
  private final AgentSpan span;
  private final BiConsumer<AgentSpan, T> decorate;
  private final AtomicBoolean finished = new AtomicBoolean(false);

  private HttpResponseWrapper(
      HttpResponseFor<T> delegate, AgentSpan span, BiConsumer<AgentSpan, T> decorate) {
    this.delegate = delegate;
    this.span = span;
    this.decorate = decorate;
  }

  @Override
  public T parse() {
    T parsed;
    try {
      parsed = delegate.parse();
    } catch (Throwable err) {
      DECORATE.finishSpan(span, err);
      finished.set(true);
      throw err;
    }
    try {
      decorate.accept(span, parsed);
    } catch (Throwable t) {
      log.debug("Span decorator failed", t);
    } finally {
      DECORATE.finishSpan(span, null);
      finished.set(true);
    }
    return parsed;
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
    if (finished.compareAndSet(false, true)) {
      DECORATE.finishSpan(span, null);
    }
    delegate.close();
  }
}
