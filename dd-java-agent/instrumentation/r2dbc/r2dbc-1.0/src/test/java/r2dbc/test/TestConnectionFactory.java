package r2dbc.test;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

public class TestConnectionFactory implements ConnectionFactory {
  @Override
  public Publisher<? extends Connection> create() {
    return subscriber -> {
      subscriber.onSubscribe(
          new Subscription() {
            @Override
            public void request(long n) {
              subscriber.onNext(new TestConnection());
              subscriber.onComplete();
            }

            @Override
            public void cancel() {}
          });
    };
  }

  @Override
  public ConnectionFactoryMetadata getMetadata() {
    return () -> "TestDB";
  }
}
