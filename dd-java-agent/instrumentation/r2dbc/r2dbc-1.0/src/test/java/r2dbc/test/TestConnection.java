package r2dbc.test;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionMetadata;
import io.r2dbc.spi.IsolationLevel;
import io.r2dbc.spi.Statement;
import io.r2dbc.spi.TransactionDefinition;
import io.r2dbc.spi.ValidationDepth;
import java.time.Duration;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Stub implementation of io.r2dbc.spi.Connection for testing. Provides factory methods to create
 * TestStatement and TestBatch instances.
 */
public class TestConnection implements Connection {
  private String dbName;
  private String dbUser;
  private String host;
  private int port;
  private String dbType;

  public TestConnection() {}

  public TestConnection(String host, int port, String dbUser, String dbName, String dbType) {
    this.host = host;
    this.port = port;
    this.dbUser = dbUser;
    this.dbName = dbName;
    this.dbType = dbType;
  }

  public String getDbName() {
    return dbName;
  }

  public String getDbUser() {
    return dbUser;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public String getDbType() {
    return dbType;
  }

  @Override
  public Publisher<Void> beginTransaction() {
    return emptyPublisher();
  }

  @Override
  public Publisher<Void> beginTransaction(TransactionDefinition definition) {
    return emptyPublisher();
  }

  @Override
  public Publisher<Void> close() {
    return emptyPublisher();
  }

  @Override
  public Publisher<Void> commitTransaction() {
    return emptyPublisher();
  }

  @Override
  public Batch createBatch() {
    return new TestBatch();
  }

  @Override
  public Publisher<Void> createSavepoint(String name) {
    return emptyPublisher();
  }

  @Override
  public Statement createStatement(String sql) {
    return new TestStatement(sql);
  }

  @Override
  public boolean isAutoCommit() {
    return true;
  }

  @Override
  public ConnectionMetadata getMetadata() {
    return new ConnectionMetadata() {
      @Override
      public String getDatabaseProductName() {
        return "TestDB";
      }

      @Override
      public String getDatabaseVersion() {
        return "1.0";
      }
    };
  }

  @Override
  public IsolationLevel getTransactionIsolationLevel() {
    return IsolationLevel.READ_COMMITTED;
  }

  @Override
  public Publisher<Void> releaseSavepoint(String name) {
    return emptyPublisher();
  }

  @Override
  public Publisher<Void> rollbackTransaction() {
    return emptyPublisher();
  }

  @Override
  public Publisher<Void> rollbackTransactionToSavepoint(String name) {
    return emptyPublisher();
  }

  @Override
  public Publisher<Void> setAutoCommit(boolean autoCommit) {
    return emptyPublisher();
  }

  @Override
  public Publisher<Void> setTransactionIsolationLevel(IsolationLevel isolationLevel) {
    return emptyPublisher();
  }

  @Override
  public Publisher<Boolean> validate(ValidationDepth depth) {
    return new Publisher<Boolean>() {
      @Override
      public void subscribe(Subscriber<? super Boolean> subscriber) {
        subscriber.onSubscribe(
            new Subscription() {
              @Override
              public void request(long n) {
                subscriber.onNext(true);
                subscriber.onComplete();
              }

              @Override
              public void cancel() {}
            });
      }
    };
  }

  @Override
  public Publisher<Void> setLockWaitTimeout(Duration timeout) {
    return emptyPublisher();
  }

  @Override
  public Publisher<Void> setStatementTimeout(Duration timeout) {
    return emptyPublisher();
  }

  private static Publisher<Void> emptyPublisher() {
    return new Publisher<Void>() {
      @Override
      public void subscribe(Subscriber<? super Void> subscriber) {
        subscriber.onSubscribe(
            new Subscription() {
              @Override
              public void request(long n) {
                subscriber.onComplete();
              }

              @Override
              public void cancel() {}
            });
      }
    };
  }
}
