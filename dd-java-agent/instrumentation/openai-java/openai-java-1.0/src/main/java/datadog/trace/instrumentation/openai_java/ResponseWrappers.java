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

  public static HttpResponseFor<Completion> wrapResponse(HttpResponseFor<Completion> response, AgentSpan span) {
    return new DDHttpResponseFor<Completion>(response) {
      @Override
      public Completion afterParse(Completion completion) {
        DECORATE.decorate(span, completion);
        return completion;
      }
    };
  }

  public static CompletableFuture<HttpResponseFor<Completion>> wrapFutureResponse(CompletableFuture<HttpResponseFor<Completion>> future, AgentSpan span) {
    return future
        .thenApply(response -> wrapResponse(response, span))
        .whenComplete((r, t) -> {
          DECORATE.beforeFinish(span);
          span.finish();
        });
  }

  public static HttpResponseFor<StreamResponse<Completion>> wrapStreamResponse(HttpResponseFor<StreamResponse<Completion>> response, final AgentSpan span) {
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
              DECORATE.decorate(span, completions);
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
