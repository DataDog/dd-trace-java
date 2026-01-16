package annotatedsample;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.concurrent.CountDownLatch;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class ReactiveStreamsTracedMethods {
  @WithSpan
  public static Publisher<String> traceAsyncPublisher(CountDownLatch latch) {
    return TestPublisher.ofComplete(latch, "hello");
  }

  @WithSpan
  public static Publisher<String> traceAsyncFailingPublisher(
      CountDownLatch latch, Throwable throwable) {
    return TestPublisher.ofFailing(latch, throwable);
  }

  @WithSpan
  public static Publisher<String> traceNestedAsyncPublisher(CountDownLatch latch) {
    return traceAsyncPublisher(latch);
  }

  public static class TestPublisher implements Publisher<String> {
    private final CountDownLatch latch;
    private final String element;
    private final Throwable error;

    private TestPublisher(CountDownLatch latch, String element, Throwable error) {
      this.latch = latch;
      this.element = element;
      this.error = error;
    }

    public static TestPublisher ofComplete(CountDownLatch latch, String element) {
      return new TestPublisher(latch, element, null);
    }

    public static TestPublisher ofFailing(CountDownLatch latch, Throwable error) {
      return new TestPublisher(latch, null, error);
    }

    @Override
    public void subscribe(Subscriber<? super String> s) {
      try {
        if (!this.latch.await(5, SECONDS)) {
          throw new IllegalStateException("Latch still locked");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }

      s.onSubscribe(
          new Subscription() {
            @Override
            public void request(long n) {
              if (error != null) {
                s.onError(error);
              } else {
                s.onNext(element);
                s.onComplete();
              }
            }

            @Override
            public void cancel() {}
          });
    }
  }

  public static class ConsumerSubscriber<T> implements Subscriber<T> {
    @Override
    public void onSubscribe(Subscription s) {
      s.request(1);
    }

    @Override
    public void onNext(T t) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onComplete() {}
  }

  public static class CancellerSubscriber<T> implements Subscriber<T> {
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
