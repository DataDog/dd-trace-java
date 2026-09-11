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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
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
import r2dbc.test.TestConnection;
import r2dbc.test.TestStatement;

/**
 * Forked test that runs with DBM propagation mode set to "full". This enables SQL comment injection
 * and the _dd.dbm_trace_injected tag on database spans.
 *
 * <p>Uses ForkedTest suffix so the test runs in a separate JVM where the DBM config is applied
 * before R2dbcDecorator's static fields are initialized.
 */
@datadog.trace.test.junit.utils.config.WithConfig(key = "dbm.propagation.mode", value = "full")
class R2dbcDBMForkedTest extends AbstractInstrumentationTest {

  @Test
  void statementExecuteInjectsDBMCommentAndSetsTraceTags() {
    TestConnection connection = new TestConnection();
    Statement statement = connection.createStatement("SELECT * FROM users");

    // Verify SQL comment was injected into the statement received by the driver
    String injectedSql = ((TestStatement) statement).getSql();
    assertNotNull(injectedSql, "SQL should not be null");
    assertTrue(
        injectedSql.startsWith("/*"), "SQL should start with DBM comment, got: " + injectedSql);
    assertTrue(
        injectedSql.contains("ddps="),
        "DBM comment should contain parent service tag, got: " + injectedSql);
    assertTrue(
        injectedSql.endsWith("SELECT * FROM users"),
        "SQL should end with original query, got: " + injectedSql);

    runnableUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          blockOnPublisher(publisher);
        });

    // Verify the span has the original SQL as resource (not the DBM-commented version)
    // and includes the _dd.dbm_trace_injected tag
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
                    tag(DB_OPERATION, is("SELECT")),
                    tag(InstrumentationTags.DBM_TRACE_INJECTED, is(true)))));
  }

  @Test
  void statementExecuteWithInsertInjectsDBMComment() {
    TestConnection connection = new TestConnection();
    Statement statement = connection.createStatement("INSERT INTO users (name) VALUES ('Alice')");

    // Verify SQL comment injection
    String injectedSql = ((TestStatement) statement).getSql();
    assertTrue(
        injectedSql.startsWith("/*"), "SQL should start with DBM comment, got: " + injectedSql);
    assertTrue(
        injectedSql.endsWith("INSERT INTO users (name) VALUES ('Alice')"),
        "SQL should end with original query, got: " + injectedSql);

    runnableUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          blockOnPublisher(publisher);
        });

    assertTraces(
        trace(
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName("r2dbc.query")
                .resourceName("INSERT INTO users (name) VALUES ('Alice')")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("testdb")),
                    tag(DB_OPERATION, is("INSERT")),
                    tag(InstrumentationTags.DBM_TRACE_INJECTED, is(true)))));
  }

  @Test
  void statementExecuteWithErrorStillHasDBMTags() {
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
                    tag(InstrumentationTags.DBM_TRACE_INJECTED, is(true)),
                    error(RuntimeException.class, "query failed"),
                    tag("error.type", is(RuntimeException.class.getName())),
                    tag("error.message", is("query failed")))));
  }

  @Test
  void dbmCommentIsNotInjectedTwice() {
    TestConnection connection = new TestConnection();
    Statement statement = connection.createStatement("SELECT 1");

    // Get the injected SQL
    String injectedSql = ((TestStatement) statement).getSql();
    assertTrue(injectedSql.startsWith("/*"), "Should have DBM comment");

    // Count how many comment blocks there are
    int commentCount = 0;
    int idx = 0;
    while ((idx = injectedSql.indexOf("/*", idx)) != -1) {
      commentCount++;
      idx += 2;
    }
    assertTrue(
        commentCount == 1,
        "Should have exactly one comment block, found " + commentCount + " in: " + injectedSql);

    runnableUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          blockOnPublisher(publisher);
        });

    // Span resource should use the original SQL, not the commented one
    assertTraces(
        trace(
            span().root().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName("r2dbc.query")
                .resourceName("SELECT 1")
                .type(DDSpanTypes.SQL)
                .measured()
                .tags(
                    defaultTags(),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(COMPONENT, is("r2dbc")),
                    tag(DB_TYPE, is("testdb")),
                    tag(DB_OPERATION, is("SELECT")),
                    tag(InstrumentationTags.DBM_TRACE_INJECTED, is(true)))));
  }

  /** Subscribes to a Publisher and blocks until completion or error. */
  private static <T> void blockOnPublisher(Publisher<T> publisher) {
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
  }
}
