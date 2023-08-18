package annotatedsample;

import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.time.Duration;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class ReactiveStreamsTracedMethods {
  public static final Duration DELAY = Duration.ofMillis(500);

  @WithSpan
  public static Publisher<String> traceAsyncPublisher() {
    return new SlowStringPublisher();
  }

  @WithSpan
  public static Publisher<String> traceAsyncFailingPublisher(Throwable throwable) {
    return new SlowFailingPublisher(throwable);
  }

  private static class SlowStringPublisher implements Publisher<String> {
    @Override
    public void subscribe(Subscriber<? super String> s) {
      sleep();
      s.onNext("hello");
      s.onComplete();
    }
  }

  private static class SlowFailingPublisher implements Publisher<String> {
    private final Throwable throwable;

    private SlowFailingPublisher(Throwable throwable) {
      this.throwable = throwable;
    }

    @Override
    public void subscribe(Subscriber<? super String> s) {
      sleep();
      s.onError(throwable);
    }
  }

  private static void sleep() {
    try {
      Thread.sleep(DELAY.toMillis()); // Wait enough time to prevent test flakiness
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public static class StubSubscriber<T> implements Subscriber<T> {
    @Override
    public void onSubscribe(Subscription s) {}

    @Override
    public void onNext(T t) {}

    @Override
    public void onError(Throwable t) {}

    @Override
    public void onComplete() {}
  }
}
