package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

public class HttpResponseWrappers {

  abstract static class DDHttpResponseFor<T> implements HttpResponseFor<T> {
    private final HttpResponseFor<T> delegate;

    DDHttpResponseFor(HttpResponseFor<T> delegate) {
      this.delegate = delegate;
    }

    abstract T afterParse(T resp);

    @Override
    public T parse() {
      return afterParse(delegate.parse());
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
      delegate.close();
    }
  }

  public static <T> HttpResponseFor<T> wrapHttpResponse(
      HttpResponseFor<T> response, AgentSpan span, BiConsumer<AgentSpan, T> afterParse) {
    DECORATE.withHttpResponse(span, response.headers());
    return new DDHttpResponseFor<T>(response) {
      @Override
      public T afterParse(T t) {
        afterParse.accept(span, t);
        return t;
      }
    };
  }

  public static <T> CompletableFuture<HttpResponseFor<T>> wrapFutureHttpResponse(
      CompletableFuture<HttpResponseFor<T>> future,
      AgentSpan span,
      BiConsumer<AgentSpan, T> afterParse) {
    return future
        .thenApply(response -> wrapHttpResponse(response, span, afterParse))
        .whenComplete(
            (r, t) -> {
              DECORATE.beforeFinish(span);
              span.finish();
            });
  }

  public static <T> HttpResponseFor<StreamResponse<T>> wrapHttpResponseStream(
      HttpResponseFor<StreamResponse<T>> response,
      final AgentSpan span,
      BiConsumer<AgentSpan, List<T>> decorate) {
    DECORATE.withHttpResponse(span, response.headers());
    return new DDHttpResponseFor<StreamResponse<T>>(response) {
      @Override
      public StreamResponse<T> afterParse(StreamResponse<T> streamResponse) {
        return new StreamResponse<T>() {
          final List<T> chunks = new ArrayList<>();

          @NotNull
          @Override
          public Stream<T> stream() {
            return streamResponse.stream().peek(chunks::add).onClose(this::close);
          }

          @Override
          public void close() {
            try {
              streamResponse.close();
              decorate.accept(span, chunks);
              DECORATE.beforeFinish(span);
            } finally {
              span.finish();
            }
          }
        };
      }
    };
  }

  public static <T>
      CompletableFuture<HttpResponseFor<StreamResponse<T>>> wrapFutureHttpResponseStream(
          CompletableFuture<HttpResponseFor<StreamResponse<T>>> future,
          AgentSpan span,
          BiConsumer<AgentSpan, List<T>> decorate) {
    return future.thenApply(r -> wrapHttpResponseStream(r, span, decorate));
  }
}
