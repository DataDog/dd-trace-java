package datadog.trace.instrumentation.rxjava2;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.AsyncResultDecorator;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;

public class RxJavaAsyncResultSupportExtension
    implements AsyncResultDecorator.AsyncResultSupportExtension {
  static {
    AsyncResultDecorator.registerExtension(new RxJavaAsyncResultSupportExtension());
  }

  /**
   * Register the extension as an {@link AsyncResultDecorator.AsyncResultSupportExtension} using
   * static class initialization.<br>
   * It uses an empty static method call to ensure the class loading and the one-time-only static
   * class initialization. This will ensure this extension will only be registered once to the
   * {@link AsyncResultDecorator}.
   */
  public static void initialize() {}

  @Override
  public boolean supports(Class<?> result) {
    return Completable.class.isAssignableFrom(result)
        || Maybe.class.isAssignableFrom(result)
        || Single.class.isAssignableFrom(result)
        || Observable.class.isAssignableFrom(result)
        || Flowable.class.isAssignableFrom(result);
  }

  @Override
  public Object apply(Object result, AgentSpan span) {
    if (result instanceof Completable) {
      return ((Completable) result)
          .doOnEvent(throwable -> onError(span, throwable))
          .doOnDispose(span::finish);
    } else if (result instanceof Maybe) {
      return ((Maybe<?>) result)
          .doOnEvent((o, throwable) -> onError(span, throwable))
          .doOnDispose(span::finish);
    } else if (result instanceof Single) {
      return ((Single<?>) result)
          .doOnEvent((o, throwable) -> onError(span, throwable))
          .doOnDispose(span::finish);
    } else if (result instanceof Observable) {
      return ((Observable<?>) result)
          .doOnComplete(span::finish)
          .doOnError(throwable -> onError(span, throwable))
          .doOnDispose(span::finish);
    } else if (result instanceof Flowable) {
      return ((Flowable<?>) result)
          .doOnComplete(span::finish)
          .doOnError(throwable -> onError(span, throwable))
          .doOnCancel(span::finish);
    }
    return null;
  }

  private static void onError(AgentSpan span, Throwable throwable) {
    span.addThrowable(throwable);
    span.finish();
  }
}
