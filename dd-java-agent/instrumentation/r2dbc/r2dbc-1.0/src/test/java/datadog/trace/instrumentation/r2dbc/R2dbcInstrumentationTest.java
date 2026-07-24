package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_OPERATION;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.api.DDSpanTypes;
import io.r2dbc.spi.Batch;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import r2dbc.test.TestBatch;
import r2dbc.test.TestConnection;
import r2dbc.test.TestStatement;

class R2dbcInstrumentationTest extends AbstractInstrumentationTest {

  @Test
  void statementExecuteCreatesSpan() {
    TestConnection connection = new TestConnection();
    Statement statement = connection.createStatement("SELECT * FROM users");

    List<?>[] results = new List<?>[1];
    runnableUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          results[0] = blockOnPublisher(publisher);
        });
    assertFalse(results[0].isEmpty(), "Statement execute should return results");

    assertTraces(
        trace(
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName("r2dbc.query")
                .resourceName("SELECT * FROM users")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("testdb")),
                    tag(DB_OPERATION, is("SELECT")))));
  }

  @Test
  void statementExecuteWithInsert() {
    TestConnection connection = new TestConnection();
    Statement statement = connection.createStatement("INSERT INTO users (name) VALUES ($1)");

    List<?>[] results = new List<?>[1];
    runnableUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          results[0] = blockOnPublisher(publisher);
        });
    assertFalse(results[0].isEmpty(), "Statement execute should return results");

    assertTraces(
        trace(
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName("r2dbc.query")
                .resourceName("INSERT INTO users (name) VALUES ($1)")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("testdb")),
                    tag(DB_OPERATION, is("INSERT")))));
  }

  @Test
  void statementExecuteWithError() {
    TestConnection connection = new TestConnection();
    Statement statement = connection.createStatement("SELECT * FROM nonexistent");
    ((TestStatement) statement).setFailOnExecute(true);

    try {
      runnableUnderTrace(
          "parent",
          () -> {
            Publisher<? extends Result> publisher = statement.execute();
            blockOnPublisher(publisher);
          });
    } catch (RuntimeException ignored) {
      // expected
    }

    assertTraces(
        trace(
            span().root().operationName("parent").error(),
            span()
                .childOfPrevious()
                .operationName("r2dbc.query")
                .resourceName("SELECT * FROM nonexistent")
                .type(DDSpanTypes.SQL)
                .error()
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("testdb")),
                    tag(DB_OPERATION, is("SELECT")),
                    error(RuntimeException.class, "query failed"))));
  }

  @Test
  void batchExecuteCreatesSpan() {
    TestConnection connection = new TestConnection();
    Batch batch = connection.createBatch();
    batch.add("INSERT INTO users (name) VALUES ('Alice')");
    batch.add("INSERT INTO users (name) VALUES ('Bob')");

    List<?>[] results = new List<?>[1];
    runnableUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = batch.execute();
          results[0] = blockOnPublisher(publisher);
        });
    assertFalse(results[0].isEmpty(), "Batch execute should return results");

    assertTraces(
        trace(
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName("r2dbc.batch")
                .resourceName("r2dbc.batch")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("r2dbc")))));
  }

  @Test
  void batchExecuteWithError() {
    TestConnection connection = new TestConnection();
    TestBatch batch = (TestBatch) connection.createBatch();
    batch.add("INSERT INTO users (name) VALUES ('Alice')");
    batch.setFailOnExecute(true);

    try {
      runnableUnderTrace(
          "parent",
          () -> {
            Publisher<? extends Result> publisher = batch.execute();
            blockOnPublisher(publisher);
          });
    } catch (RuntimeException ignored) {
      // expected
    }

    assertTraces(
        trace(
            span().root().operationName("parent").error(),
            span()
                .childOfPrevious()
                .operationName("r2dbc.batch")
                .resourceName("r2dbc.batch")
                .type(DDSpanTypes.SQL)
                .error()
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("r2dbc")),
                    error(RuntimeException.class, "batch failed"))));
  }

  @Test
  void multipleStatementExecutesCreateSeparateSpans() {
    TestConnection connection = new TestConnection();
    Statement statement1 = connection.createStatement("SELECT * FROM users");
    Statement statement2 = connection.createStatement("UPDATE users SET name = 'test'");

    runnableUnderTrace(
        "parent",
        () -> {
          blockOnPublisher(statement1.execute());
          blockOnPublisher(statement2.execute());
        });

    assertTraces(
        trace(
            TraceMatcher.SORT_BY_ANCESTRY,
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName("r2dbc.query")
                .resourceName("SELECT * FROM users")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("testdb")),
                    tag(DB_OPERATION, is("SELECT"))),
            span()
                .childOfIndex(0)
                .operationName("r2dbc.query")
                .resourceName("UPDATE users SET name = 'test'")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("testdb")),
                    tag(DB_OPERATION, is("UPDATE")))));
  }

  @Test
  void statementExecuteWithNoParentSpan() {
    TestConnection connection = new TestConnection();
    Statement statement = connection.createStatement("SELECT 1");

    Publisher<? extends Result> publisher = statement.execute();
    List<?> results = blockOnPublisher(publisher);
    assertFalse(results.isEmpty(), "Statement execute should return results");

    assertTraces(
        trace(
            span()
                .root()
                .operationName("r2dbc.query")
                .resourceName("SELECT 1")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("testdb")),
                    tag(DB_OPERATION, is("SELECT")))));
  }

  /** Subscribes to a Publisher and blocks until completion or error. Returns collected results. */
  private static <T> List<T> blockOnPublisher(Publisher<T> publisher) {
    List<T> results = new ArrayList<>();
    RuntimeException[] error = new RuntimeException[1];
    CountDownLatch latch = new CountDownLatch(1);
    publisher.subscribe(
        new Subscriber<T>() {
          @Override
          public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(T t) {
            results.add(t);
          }

          @Override
          public void onError(Throwable t) {
            if (t instanceof RuntimeException) {
              error[0] = (RuntimeException) t;
            } else {
              error[0] = new RuntimeException(t);
            }
            latch.countDown();
          }

          @Override
          public void onComplete() {
            latch.countDown();
          }
        });
    try {
      latch.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted waiting for publisher", e);
    }
    if (error[0] != null) {
      throw error[0];
    }
    return results;
  }
}
