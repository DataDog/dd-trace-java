package r2dbc.test;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Result;
import java.util.ArrayList;
import java.util.List;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

public class TestBatch implements Batch {
  private final List<String> statements = new ArrayList<>();
  private boolean failOnExecute;

  public void setFailOnExecute(boolean fail) {
    this.failOnExecute = fail;
  }

  @Override
  public Batch add(String sql) {
    statements.add(sql);
    return this;
  }

  @Override
  public Publisher<? extends Result> execute() {
    if (failOnExecute) {
      return subscriber -> {
        subscriber.onSubscribe(
            new Subscription() {
              @Override
              public void request(long n) {
                subscriber.onError(new RuntimeException("batch failed"));
              }

              @Override
              public void cancel() {}
            });
      };
    }
    return subscriber -> {
      subscriber.onSubscribe(
          new Subscription() {
            @Override
            public void request(long n) {
              subscriber.onNext(new TestResult());
              subscriber.onComplete();
            }

            @Override
            public void cancel() {}
          });
    };
  }
}
