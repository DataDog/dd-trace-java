package datadog.trace.instrumentation.springmessaging;

import static datadog.trace.instrumentation.springmessaging.SpringMessageDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class SpringMessageAsyncHelper {
  private SpringMessageAsyncHelper() {}

  private static final ClassValue<ReactorCallbackMethods> REACTOR_CALLBACK_METHODS =
      new ReactorCallbacksClassValue();

  public static Object wrapAsyncResult(Object result, AgentSpan span) {
    if (result == null) {
      return null;
    }
    SpanFinisher finisher = new SpanFinisher(span);
    if (result instanceof CompletionStage<?>) {
      return ((CompletionStage<?>) result)
          .whenComplete(new CompletionStageFinishCallback(finisher));
    }
    ReactorCallbackMethods callbackMethods = REACTOR_CALLBACK_METHODS.get(result.getClass());
    if (!callbackMethods.supported()) {
      return null;
    }
    try {
      Object wrapped = callbackMethods.doOnError.invoke(result, new ErrorCallback(finisher));
      wrapped = callbackMethods.doOnTerminate.invoke(wrapped, new FinishCallback(finisher));
      return callbackMethods.doOnCancel.invoke(wrapped, new FinishCallback(finisher));
    } catch (Throwable ignored) {
      return null;
    }
  }

  static final class ReactorCallbacksClassValue extends ClassValue<ReactorCallbackMethods> {
    @Override
    protected ReactorCallbackMethods computeValue(Class<?> type) {
      try {
        Method doOnError = type.getMethod("doOnError", Consumer.class);
        Method doOnTerminate = type.getMethod("doOnTerminate", Runnable.class);
        Method doOnCancel = type.getMethod("doOnCancel", Runnable.class);
        return new ReactorCallbackMethods(doOnError, doOnTerminate, doOnCancel);
      } catch (Throwable ignored) {
        return ReactorCallbackMethods.UNSUPPORTED;
      }
    }
  }

  static final class ReactorCallbackMethods {
    static final ReactorCallbackMethods UNSUPPORTED = new ReactorCallbackMethods(null, null, null);

    final Method doOnError;
    final Method doOnTerminate;
    final Method doOnCancel;

    ReactorCallbackMethods(Method doOnError, Method doOnTerminate, Method doOnCancel) {
      this.doOnError = doOnError;
      this.doOnTerminate = doOnTerminate;
      this.doOnCancel = doOnCancel;
    }

    boolean supported() {
      return doOnError != null && doOnTerminate != null && doOnCancel != null;
    }
  }

  static final class SpanFinisher {
    private final AgentSpan span;
    private final AtomicBoolean finished = new AtomicBoolean(false);

    SpanFinisher(AgentSpan span) {
      this.span = span;
    }

    void onError(Throwable throwable) {
      DECORATE.onError(span, throwable);
    }

    void finish() {
      if (finished.compareAndSet(false, true)) {
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }

  static final class CompletionStageFinishCallback implements BiConsumer<Object, Throwable> {
    private final SpanFinisher finisher;

    CompletionStageFinishCallback(SpanFinisher finisher) {
      this.finisher = finisher;
    }

    @Override
    public void accept(Object ignored, Throwable throwable) {
      if (throwable != null) {
        finisher.onError(unwrap(throwable));
      }
      finisher.finish();
    }
  }

  static final class ErrorCallback implements Consumer<Throwable> {
    private final SpanFinisher finisher;

    ErrorCallback(SpanFinisher finisher) {
      this.finisher = finisher;
    }

    @Override
    public void accept(Throwable throwable) {
      finisher.onError(throwable);
    }
  }

  static final class FinishCallback implements Runnable {
    private final SpanFinisher finisher;

    FinishCallback(SpanFinisher finisher) {
      this.finisher = finisher;
    }

    @Override
    public void run() {
      finisher.finish();
    }
  }

  private static Throwable unwrap(Throwable throwable) {
    if (throwable instanceof CompletionException || throwable instanceof ExecutionException) {
      Throwable cause = throwable.getCause();
      if (cause != null) {
        return cause;
      }
    }
    return throwable;
  }
}
