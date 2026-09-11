package r2dbc.test;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

public class TestResult implements Result {
  @Override
  public Publisher<Long> getRowsUpdated() {
    return subscriber -> {
      subscriber.onSubscribe(
          new Subscription() {
            @Override
            public void request(long n) {
              subscriber.onNext(0L);
              subscriber.onComplete();
            }

            @Override
            public void cancel() {}
          });
    };
  }

  @Override
  public <T> Publisher<T> map(BiFunction<Row, RowMetadata, ? extends T> mappingFunction) {
    return subscriber -> {
      subscriber.onSubscribe(
          new Subscription() {
            @Override
            public void request(long n) {
              subscriber.onComplete();
            }

            @Override
            public void cancel() {}
          });
    };
  }

  @Override
  public Result filter(Predicate<Segment> filter) {
    return this;
  }

  @Override
  public <T> Publisher<T> flatMap(
      Function<Segment, ? extends Publisher<? extends T>> mappingFunction) {
    return subscriber -> {
      subscriber.onSubscribe(
          new Subscription() {
            @Override
            public void request(long n) {
              subscriber.onComplete();
            }

            @Override
            public void cancel() {}
          });
    };
  }
}
