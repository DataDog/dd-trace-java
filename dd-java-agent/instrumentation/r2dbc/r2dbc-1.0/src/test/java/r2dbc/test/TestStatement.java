package r2dbc.test;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/** Stub implementation of io.r2dbc.spi.Statement for testing. */
public class TestStatement implements Statement {
  private final String sql;
  private boolean shouldFail;

  public TestStatement(String sql) {
    this.sql = sql;
  }

  public void setShouldFail(boolean shouldFail) {
    this.shouldFail = shouldFail;
  }

  public String getSql() {
    return sql;
  }

  @Override
  public Statement add() {
    return this;
  }

  @Override
  public Statement bind(int index, Object value) {
    return this;
  }

  @Override
  public Statement bind(String name, Object value) {
    return this;
  }

  @Override
  public Statement bindNull(int index, Class<?> type) {
    return this;
  }

  @Override
  public Statement bindNull(String name, Class<?> type) {
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
                  subscriber.onError(new RuntimeException("query execution failed"));
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

  @Override
  public Statement returnGeneratedValues(String... columns) {
    return this;
  }

  @Override
  public Statement fetchSize(int rows) {
    return this;
  }
}
