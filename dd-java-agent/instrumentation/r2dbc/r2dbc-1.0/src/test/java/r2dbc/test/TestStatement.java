package r2dbc.test;

import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;

public class TestStatement implements Statement {
  private final String sql;
  private boolean failOnExecute;

  public TestStatement(String sql) {
    this.sql = sql;
  }

  public String getSql() {
    return sql;
  }

  public void setFailOnExecute(boolean fail) {
    this.failOnExecute = fail;
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
    if (failOnExecute) {
      return subscriber -> {
        subscriber.onSubscribe(
            new Subscription() {
              @Override
              public void request(long n) {
                subscriber.onError(new RuntimeException("query failed"));
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
