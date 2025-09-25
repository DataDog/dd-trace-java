package annotatedsample;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import java.util.concurrent.CountDownLatch;

public class RxJava2TracedMethods {
  @WithSpan
  public static Completable traceAsyncCompletable(CountDownLatch latch) {
    return Completable.fromRunnable(() -> await(latch));
  }

  @WithSpan
  public static Completable traceAsyncFailingCompletable(
      CountDownLatch latch, Exception exception) {
    return Completable.fromCallable(
        () -> {
          await(latch);
          throw exception;
        });
  }

  @WithSpan
  public static Maybe<String> traceAsyncMaybe(CountDownLatch latch) {
    return Maybe.fromCallable(
        () -> {
          await(latch);
          return "hello";
        });
  }

  @WithSpan
  public static Maybe<String> traceAsyncFailingMaybe(CountDownLatch latch, Exception exception) {
    return Maybe.fromCallable(
        () -> {
          await(latch);
          throw exception;
        });
  }

  @WithSpan
  public static Single<String> traceAsyncSingle(CountDownLatch latch) {
    return Single.fromCallable(
        () -> {
          await(latch);
          return "hello";
        });
  }

  @WithSpan
  public static Single<String> traceAsyncFailingSingle(CountDownLatch latch, Exception exception) {
    return Single.fromCallable(
        () -> {
          await(latch);
          throw exception;
        });
  }

  @WithSpan
  public static Observable<String> traceAsyncObservable(CountDownLatch latch) {
    return Observable.fromCallable(
        () -> {
          await(latch);
          return "hello";
        });
  }

  @WithSpan
  public static Observable<String> traceAsyncFailingObservable(
      CountDownLatch latch, Exception exception) {
    return Observable.fromCallable(
        () -> {
          await(latch);
          throw exception;
        });
  }

  @WithSpan
  public static Flowable<String> traceAsyncFlowable(CountDownLatch latch) {
    return Flowable.fromCallable(
        () -> {
          await(latch);
          return "hello";
        });
  }

  @WithSpan
  public static Flowable<String> traceAsyncFailingFlowable(
      CountDownLatch latch, Exception exception) {
    return Flowable.fromCallable(
        () -> {
          await(latch);
          throw exception;
        });
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, SECONDS)) {
        throw new IllegalStateException("Latch still locked");
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
