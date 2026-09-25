package r2dbc.test;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Result;
import java.util.ArrayList;
import java.util.List;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/** Stub implementation of io.r2dbc.spi.Batch for testing. */
public class TestBatch implements Batch {
  private final List<String> statements = new ArrayList<>();
  private boolean shouldFail;

  public void setShouldFail(boolean shouldFail) {
    this.shouldFail = shouldFail;
  }

  @Override
  public Batch add(String sql) {
    statements.add(sql);
    return this;
  }

  @Override
  public Publisher<? extends Result> execute() {
    return new Publisher<Result>() {
      @Override
      public void subscribe(Subscriber<? super Result> subscriber) {
        subscriber.onSubscribe(
            new Subscription() {
              @Override
              public void request(long n) {
                if (shouldFail) {
                  subscriber.onError(new RuntimeException("batch execution failed"));
                } else {
                  subscriber.onComplete();
                }
              }

              @Override
              public void cancel() {}
            });
      }
    };
  }
}
