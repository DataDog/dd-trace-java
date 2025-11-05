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
import java.util.stream.Stream;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

public class StreamHelpers {

  public static HttpResponseFor<StreamResponse<Completion>> wrap(HttpResponseFor<StreamResponse<Completion>> response, final AgentSpan span) {
    return new HttpResponseFor<StreamResponse<Completion>>() {
      @Override
      public StreamResponse<Completion> parse() {
        return new StreamResponse<Completion>() {
          @NotNull
          @Override
          public Stream<Completion> stream() {
            final List<Completion> completions = new ArrayList<>();
            return response.parse().stream()
                .peek(completions::add)
                .onClose(() -> {
                  DECORATE.beforeFinish(span);
                  DECORATE.decorate(span, completions);
                  span.finish();
                });
          }

          @Override
          public void close() {
            response.parse().close();
          }
        };
      }

      @Override
      public int statusCode() {
        return response.statusCode();
      }

      @NotNull
      @Override
      public Headers headers() {
        return response.headers();
      }

      @NotNull
      @Override
      public InputStream body() {
        return response.body();
      }

      @Override
      public void close() {
        response.close();
      }
    };
  }
}
