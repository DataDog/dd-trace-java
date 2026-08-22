package datadog.trace.instrumentation.r2dbc;

import datadog.trace.bootstrap.ContextStore;
import io.r2dbc.spi.Connection;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Wraps a {@link Publisher} returned by {@code ConnectionFactory.create()} to associate each
 * emitted {@link Connection} with its {@link R2dbcConnectionInfo} in the context store. This
 * enables downstream Statement and Batch advice to access connection metadata for DBM tagging.
 */
public final class MetadataWrappingPublisher implements Publisher<Connection> {

  private final Publisher<? extends Connection> delegate;
  private final R2dbcConnectionInfo info;
  private final ContextStore<Connection, R2dbcConnectionInfo> contextStore;

  public MetadataWrappingPublisher(
      Publisher<? extends Connection> delegate,
      R2dbcConnectionInfo info,
      ContextStore<Connection, R2dbcConnectionInfo> contextStore) {
    this.delegate = delegate;
    this.info = info;
    this.contextStore = contextStore;
  }

  @Override
  public void subscribe(Subscriber<? super Connection> subscriber) {
    delegate.subscribe(new MetadataWrappingSubscriber(subscriber, info, contextStore));
  }

  static final class MetadataWrappingSubscriber implements Subscriber<Connection> {

    private final Subscriber<? super Connection> delegate;
    private final R2dbcConnectionInfo info;
    private final ContextStore<Connection, R2dbcConnectionInfo> contextStore;

    MetadataWrappingSubscriber(
        Subscriber<? super Connection> delegate,
        R2dbcConnectionInfo info,
        ContextStore<Connection, R2dbcConnectionInfo> contextStore) {
      this.delegate = delegate;
      this.info = info;
      this.contextStore = contextStore;
    }

    @Override
    public void onSubscribe(Subscription s) {
      delegate.onSubscribe(s);
    }

    @Override
    public void onNext(Connection connection) {
      if (connection != null) {
        contextStore.put(connection, info);
      }
      delegate.onNext(connection);
    }

    @Override
    public void onError(Throwable t) {
      delegate.onError(t);
    }

    @Override
    public void onComplete() {
      delegate.onComplete();
    }
  }
}
