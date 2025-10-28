package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.instrumentation.lettuce5.LettuceClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;

/**
 * Callback class to close the span on an error or a success in the RedisFuture returned by the
 * lettuce async API
 *
 * @param <T> the normal completion result
 * @param <U> the error
 * @param <R> the return type, should be null since nothing else should happen from tracing
 *     standpoint after the span is closed
 */
public class LettuceAsyncBiConsumer<T extends Object, U extends Throwable>
    implements BiConsumer<T, Throwable> {

  private final AgentSpan span;

  public LettuceAsyncBiConsumer(final AgentSpan span) {
    this.span = span;
  }

  @Override
  public void accept(final T t, final Throwable throwable) {
    if (throwable instanceof CancellationException) {
      span.setTag("db.command.cancelled", true);
    } else {
      DECORATE.onError(span, throwable);
    }
    DECORATE.beforeFinish(span);
    span.finish();
  }
}
