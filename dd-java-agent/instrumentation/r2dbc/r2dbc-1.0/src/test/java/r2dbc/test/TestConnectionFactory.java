package r2dbc.test;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryMetadata;
import io.r2dbc.spi.ConnectionFactoryOptions;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Stub implementation of io.r2dbc.spi.ConnectionFactory for testing. Creates TestConnection
 * instances with configurable connection metadata for DBM testing.
 */
public class TestConnectionFactory implements ConnectionFactory {
  private final ConnectionFactoryOptions options;

  public TestConnectionFactory(ConnectionFactoryOptions options) {
    this.options = options;
  }

  public ConnectionFactoryOptions getOptions() {
    return options;
  }

  @Override
  public Publisher<? extends Connection> create() {
    String host =
        options.hasOption(ConnectionFactoryOptions.HOST)
            ? (String) options.getValue(ConnectionFactoryOptions.HOST)
            : null;
    Integer port =
        options.hasOption(ConnectionFactoryOptions.PORT)
            ? (Integer) options.getValue(ConnectionFactoryOptions.PORT)
            : 0;
    String user =
        options.hasOption(ConnectionFactoryOptions.USER)
            ? (String) options.getValue(ConnectionFactoryOptions.USER)
            : null;
    String database =
        options.hasOption(ConnectionFactoryOptions.DATABASE)
            ? (String) options.getValue(ConnectionFactoryOptions.DATABASE)
            : null;
    String driver =
        options.hasOption(ConnectionFactoryOptions.DRIVER)
            ? (String) options.getValue(ConnectionFactoryOptions.DRIVER)
            : null;

    TestConnection connection =
        new TestConnection(host, port != null ? port : 0, user, database, driver);
    return new Publisher<Connection>() {
      @Override
      public void subscribe(Subscriber<? super Connection> subscriber) {
        subscriber.onSubscribe(
            new Subscription() {
              @Override
              public void request(long n) {
                subscriber.onNext(connection);
                subscriber.onComplete();
              }

              @Override
              public void cancel() {}
            });
      }
    };
  }

  @Override
  public ConnectionFactoryMetadata getMetadata() {
    return new ConnectionFactoryMetadata() {
      @Override
      public String getName() {
        return options.hasOption(ConnectionFactoryOptions.DRIVER)
            ? (String) options.getValue(ConnectionFactoryOptions.DRIVER)
            : "test";
      }
    };
  }
}
