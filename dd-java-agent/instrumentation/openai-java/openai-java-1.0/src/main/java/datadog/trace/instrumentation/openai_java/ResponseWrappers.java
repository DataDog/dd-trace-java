package datadog.trace.instrumentation.openai_java;

import com.openai.core.http.Headers;
import com.openai.core.http.HttpResponseFor;
import com.openai.core.http.StreamResponse;
import com.openai.models.completions.Completion;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.jetbrains.annotations.NotNull;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

public class ResponseWrappers {

  static abstract class DDHttpResponseFor<T> implements HttpResponseFor<T> {
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

  public static <T> HttpResponseFor<T> wrapResponse(HttpResponseFor<T> response, AgentSpan span, BiConsumer<AgentSpan, T> afterParse) {
    DECORATE.decorateWithResponse(span, response);
    return new DDHttpResponseFor<T>(response) {
      @Override
      public T afterParse(T t) {
        afterParse.accept(span, t);
        return t;
      }
    };
  }

  public static <T> CompletableFuture<HttpResponseFor<T>> wrapFutureResponse(CompletableFuture<HttpResponseFor<T>> future, AgentSpan span, BiConsumer<AgentSpan, T> afterParse) {
    return future
        .thenApply(response ->
          wrapResponse(response, span, afterParse)
        )
        .whenComplete((r, t) -> {
          DECORATE.beforeFinish(span);
          span.finish();
        });
  }

  public static HttpResponseFor<StreamResponse<Completion>> wrapStreamResponse(HttpResponseFor<StreamResponse<Completion>> response, final AgentSpan span) {
    DECORATE.decorateWithResponse(span, response);
    return new DDHttpResponseFor<StreamResponse<Completion>>(response) {
      @Override
      public StreamResponse<Completion> afterParse(StreamResponse<Completion> streamResponse) {
        return new StreamResponse<Completion>() {
          final List<Completion> completions = new ArrayList<>();

          @NotNull
          @Override
          public Stream<Completion> stream() {
            return streamResponse
                .stream()
                .peek(completions::add)
                .onClose(this::close);
          }

          @Override
          public void close() {
            try {
              streamResponse.close();
              DECORATE.decorateWithCompletions(span, completions);
              DECORATE.beforeFinish(span);
            } finally {
              span.finish();
            }
          }
        };
      }
    };
  }

  public static CompletableFuture<HttpResponseFor<StreamResponse<Completion>>> wrapFutureStreamResponse(CompletableFuture<HttpResponseFor<StreamResponse<Completion>>> future, AgentSpan span) {
    return future.thenApply(r -> wrapStreamResponse(r, span));
  }
}
