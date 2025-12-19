package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

import com.openai.core.http.StreamResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;

public final class HttpStreamResponseStreamWrapper<T> implements StreamResponse<T> {
  private final AgentSpan span;
  private final BiConsumer<AgentSpan, List<T>> afterParse;
  private final List<T> chunks;
  private final StreamResponse<T> parsed;

  HttpStreamResponseStreamWrapper(
      AgentSpan span, BiConsumer<AgentSpan, List<T>> afterParse, StreamResponse<T> parsed) {
    this.span = span;
    this.afterParse = afterParse;
    this.parsed = parsed;
    chunks = new ArrayList<>();
  }

  @NotNull
  @Override
  public Stream<T> stream() {
    return parsed.stream().peek(chunks::add).onClose(this::close);
  }

  @Override
  public void close() {
    try {
      parsed.close();
      afterParse.accept(span, chunks);
      DECORATE.beforeFinish(span);
    } finally {
      span.finish();
    }
  }
}
