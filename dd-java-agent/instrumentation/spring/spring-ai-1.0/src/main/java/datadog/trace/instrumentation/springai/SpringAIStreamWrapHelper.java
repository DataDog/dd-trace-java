package datadog.trace.instrumentation.springai;

import static datadog.trace.instrumentation.springai.SpringAIDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class SpringAIStreamWrapHelper {
  private SpringAIStreamWrapHelper() {}

  static Object wrapPublisher(final Object publisher, final AgentSpan span) {
    if (publisher == null || span == null) {
      return publisher;
    }
    try {
      final Class<?> fluxClass = Class.forName("reactor.core.publisher.Flux");
      if (!fluxClass.isInstance(publisher)) {
        return publisher;
      }
      final StreamState state = new StreamState();
      Object flux = publisher;
      flux =
          invoke(
              flux,
              "doOnNext",
              Consumer.class,
              (Consumer<Object>)
                  chunk -> {
                    DECORATE.onTokenUsage(span, chunk);
                    final String piece = SpringAIMessageExtractAdapter.extractOutput(chunk);
                    if (piece != null) {
                      state.append(piece);
                    }
                  });
      flux =
          invoke(
              flux,
              "doOnError",
              Consumer.class,
              (Consumer<Throwable>)
                  error -> {
                    DECORATE.onOutput(span, state.output());
                    DECORATE.onError(span, error);
                    finishSpan(span, state.finished);
                  });
      flux =
          invoke(
              flux,
              "doOnComplete",
              Runnable.class,
              (Runnable)
                  () -> {
                    DECORATE.onOutput(span, state.output());
                    finishSpan(span, state.finished);
                  });
      flux =
          invoke(
              flux,
              "doOnCancel",
              Runnable.class,
              (Runnable)
                  () -> {
                    DECORATE.onOutput(span, state.output());
                    finishSpan(span, state.finished);
                  });
      return flux;
    } catch (Throwable ignored) {
      return publisher;
    }
  }

  private static void finishSpan(final AgentSpan span, final AtomicBoolean finished) {
    if (finished.compareAndSet(false, true)) {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }

  private static Object invoke(
      final Object target, final String methodName, final Class<?> argType, final Object arg)
      throws Exception {
    final Method method = target.getClass().getMethod(methodName, argType);
    return method.invoke(target, arg);
  }

  private static final class StreamState {
    private static final int MAX = 8192;
    private final StringBuilder output = new StringBuilder();
    private final AtomicBoolean finished = new AtomicBoolean(false);

    synchronized void append(final String piece) {
      if (piece == null || piece.isEmpty() || output.length() >= MAX) {
        return;
      }
      final int remaining = MAX - output.length();
      if (piece.length() <= remaining) {
        output.append(piece);
      } else {
        output.append(piece, 0, remaining);
      }
    }

    synchronized String output() {
      return output.length() == 0 ? null : output.toString();
    }
  }
}
