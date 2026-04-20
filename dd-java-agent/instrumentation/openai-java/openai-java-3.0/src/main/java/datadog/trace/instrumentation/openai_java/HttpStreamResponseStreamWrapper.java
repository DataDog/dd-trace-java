package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

import com.openai.core.http.StreamResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HttpStreamResponseStreamWrapper<T> implements StreamResponse<T> {
  private static final Logger log = LoggerFactory.getLogger(HttpStreamResponseStreamWrapper.class);

  private final AgentSpan span;
  private final BiConsumer<AgentSpan, List<T>> decorate;
  private final List<T> chunks;
  private final StreamResponse<T> parsed;
  private final AtomicBoolean finished = new AtomicBoolean(false);

  HttpStreamResponseStreamWrapper(
      AgentSpan span, BiConsumer<AgentSpan, List<T>> decorate, StreamResponse<T> parsed) {
    this.span = span;
    this.decorate = decorate;
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
    if (finished.compareAndSet(false, true)) {
      try {
        decorate.accept(span, chunks);
      } catch (Throwable t) {
        log.debug("Span decorator failed", t);
      }
      DECORATE.finishSpan(span, null);
    }
    parsed.close();
  }
}
