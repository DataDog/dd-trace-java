package annotatedsample;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.CountDownLatch;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ReactorTracedMethods {
  @WithSpan
  public static Mono<String> traceAsyncMono(CountDownLatch latch) {
    return Mono.fromCallable(
        () -> {
          await(latch);
          return "hello";
        });
  }

  @WithSpan
  public static Mono<String> traceAsyncFailingMono(CountDownLatch latch, Exception exception) {
    return Mono.fromCallable(
        () -> {
          await(latch);
          throw exception;
        });
  }

  @WithSpan
  public static Flux<String> traceAsyncFlux(CountDownLatch latch) {
    return Flux.create(
        emitter -> {
          await(latch);
          emitter.next("hello");
          emitter.complete();
        });
  }

  @WithSpan
  public static Flux<String> traceAsyncFailingFlux(CountDownLatch latch, Exception exception) {
    return Flux.create(
        emitter -> {
          await(latch);
          emitter.error(exception);
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

  public static class CancelSubscriber<T> implements Subscriber<T> {
    @Override
    public void onSubscribe(Subscription s) {
      s.cancel();
    }

    @Override
    public void onNext(T t) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onComplete() {}
  }
}
