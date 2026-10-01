package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.error;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_INSTANCE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_OPERATION;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_USER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_PORT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.test.junit.utils.assertions.Matchers.any;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.api.DDTags;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Result;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import r2dbc.test.TestBatch;
import r2dbc.test.TestConnection;
import r2dbc.test.TestConnectionFactory;
import r2dbc.test.TestStatement;

class R2dbcInstrumentationTest extends AbstractInstrumentationTest {

  /**
   * Creates a Predicate that compares CharSequence values by string content. Needed because span
   * operation and resource names are stored as UTF8BytesString which is not equal to a plain String
   * via Object.equals().
   */
  private static Predicate<CharSequence> csEquals(String expected) {
    return cs -> cs != null && cs.toString().equals(expected);
  }

  @Test
  void statementExecuteCreatesASpan() throws Exception {
    TestConnection connection = new TestConnection();
    TestStatement statement = (TestStatement) connection.createStatement("SELECT * FROM users");
    CountDownLatch latch = new CountDownLatch(1);

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          publisher.subscribe(completionSubscriber(latch));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("SELECT * FROM users"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    tag(DB_OPERATION, matches("SELECT")),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void statementExecuteWithBindParametersCreatesASpan() throws Exception {
    TestConnection connection = new TestConnection();
    TestStatement statement =
        (TestStatement) connection.createStatement("SELECT * FROM users WHERE id = ?");
    statement.bind(0, 42);
    CountDownLatch latch = new CountDownLatch(1);

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          publisher.subscribe(completionSubscriber(latch));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("SELECT * FROM users WHERE id = ?"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    tag(DB_OPERATION, matches("SELECT")),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void batchExecuteCreatesASpan() throws Exception {
    TestConnection connection = new TestConnection();
    TestBatch batch = (TestBatch) connection.createBatch();
    batch.add("INSERT INTO users (name) VALUES ('Alice')");
    batch.add("INSERT INTO users (name) VALUES ('Bob')");
    CountDownLatch latch = new CountDownLatch(1);

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = batch.execute();
          publisher.subscribe(completionSubscriber(latch));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("r2dbc.batch"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void statementExecuteWithoutParentCreatesARootSpan() throws Exception {
    TestConnection connection = new TestConnection();
    TestStatement statement = (TestStatement) connection.createStatement("SELECT 1");
    CountDownLatch latch = new CountDownLatch(1);

    Publisher<? extends Result> publisher = statement.execute();
    publisher.subscribe(completionSubscriber(latch));
    assert latch.await(10, TimeUnit.SECONDS);

    assertTraces(
        TraceMatcher.trace(
            span()
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("SELECT ?"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    tag(DB_OPERATION, matches("SELECT")),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void statementExecutePropagatesErrorToSpan() throws Exception {
    TestConnection connection = new TestConnection();
    TestStatement statement = (TestStatement) connection.createStatement("INVALID SQL");
    statement.setShouldFail(true);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> caughtError = new AtomicReference<>();

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          publisher.subscribe(errorCapturingSubscriber(latch, caughtError));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);
    assert caughtError.get() != null;

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("INVALID SQL"))
                .type("sql")
                .error()
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    tag(DB_OPERATION, matches("INVALID")),
                    error(RuntimeException.class, "query execution failed"),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void batchExecutePropagatesErrorToSpan() throws Exception {
    TestConnection connection = new TestConnection();
    TestBatch batch = (TestBatch) connection.createBatch();
    batch.add("INVALID SQL");
    batch.setShouldFail(true);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> caughtError = new AtomicReference<>();

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = batch.execute();
          publisher.subscribe(errorCapturingSubscriber(latch, caughtError));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);
    assert caughtError.get() != null;

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("r2dbc.batch"))
                .type("sql")
                .error()
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    error(RuntimeException.class, "batch execution failed"),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void statementExecuteWithConnectionMetadataSetsDbTags() throws Exception {
    ConnectionFactoryOptions options =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, "db.example.com")
            .option(ConnectionFactoryOptions.PORT, 5432)
            .option(ConnectionFactoryOptions.USER, "app_user")
            .option(ConnectionFactoryOptions.DATABASE, "myapp_db")
            .build();
    TestConnectionFactory factory = new TestConnectionFactory(options);
    AtomicReference<Connection> connectionRef = new AtomicReference<>();
    CountDownLatch connLatch = new CountDownLatch(1);
    factory
        .create()
        .subscribe(
            new Subscriber<Connection>() {
              @Override
              public void onSubscribe(Subscription s) {
                s.request(1);
              }

              @Override
              public void onNext(Connection c) {
                connectionRef.set(c);
              }

              @Override
              public void onError(Throwable t) {
                connLatch.countDown();
              }

              @Override
              public void onComplete() {
                connLatch.countDown();
              }
            });
    assert connLatch.await(10, TimeUnit.SECONDS);
    Connection connection = connectionRef.get();
    assert connection != null;
    TestStatement statement =
        (TestStatement) connection.createStatement("SELECT * FROM orders WHERE id = ?");
    statement.bind(0, 1);
    CountDownLatch latch = new CountDownLatch(1);

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          publisher.subscribe(completionSubscriber(latch));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("postgresql\\.query"))
                .resourceName(csEquals("SELECT * FROM orders WHERE id = ?"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("postgresql")),
                    tag(DB_OPERATION, matches("SELECT")),
                    tag(DB_INSTANCE, is("myapp_db")),
                    tag(DB_USER, is("app_user")),
                    tag(PEER_HOSTNAME, is("db.example.com")),
                    tag(PEER_PORT, is(5432)),
                    tag(DDTags.PEER_SERVICE_SOURCE, any()),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void batchExecuteWithConnectionMetadataSetsDbTags() throws Exception {
    ConnectionFactoryOptions options =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, "db.example.com")
            .option(ConnectionFactoryOptions.PORT, 5432)
            .option(ConnectionFactoryOptions.USER, "app_user")
            .option(ConnectionFactoryOptions.DATABASE, "myapp_db")
            .build();
    TestConnectionFactory factory = new TestConnectionFactory(options);
    AtomicReference<Connection> connectionRef = new AtomicReference<>();
    CountDownLatch connLatch = new CountDownLatch(1);
    factory
        .create()
        .subscribe(
            new Subscriber<Connection>() {
              @Override
              public void onSubscribe(Subscription s) {
                s.request(1);
              }

              @Override
              public void onNext(Connection c) {
                connectionRef.set(c);
              }

              @Override
              public void onError(Throwable t) {
                connLatch.countDown();
              }

              @Override
              public void onComplete() {
                connLatch.countDown();
              }
            });
    assert connLatch.await(10, TimeUnit.SECONDS);
    Connection connection = connectionRef.get();
    assert connection != null;
    TestBatch batch = (TestBatch) connection.createBatch();
    batch.add("INSERT INTO users (name) VALUES ('Alice')");
    batch.add("INSERT INTO users (name) VALUES ('Bob')");
    CountDownLatch latch = new CountDownLatch(1);

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = batch.execute();
          publisher.subscribe(completionSubscriber(latch));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("postgresql\\.query"))
                .resourceName(csEquals("r2dbc.batch"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("postgresql")),
                    tag(DB_INSTANCE, is("myapp_db")),
                    tag(DB_USER, is("app_user")),
                    tag(PEER_HOSTNAME, is("db.example.com")),
                    tag(PEER_PORT, is(5432)),
                    tag(DDTags.PEER_SERVICE_SOURCE, any()),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void statementExecuteErrorWithConnectionMetadataPreservesDbTags() throws Exception {
    ConnectionFactoryOptions options =
        ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, "db.example.com")
            .option(ConnectionFactoryOptions.PORT, 5432)
            .option(ConnectionFactoryOptions.USER, "app_user")
            .option(ConnectionFactoryOptions.DATABASE, "myapp_db")
            .build();
    TestConnectionFactory factory = new TestConnectionFactory(options);
    AtomicReference<Connection> connectionRef = new AtomicReference<>();
    CountDownLatch connLatch = new CountDownLatch(1);
    factory
        .create()
        .subscribe(
            new Subscriber<Connection>() {
              @Override
              public void onSubscribe(Subscription s) {
                s.request(1);
              }

              @Override
              public void onNext(Connection c) {
                connectionRef.set(c);
              }

              @Override
              public void onError(Throwable t) {
                connLatch.countDown();
              }

              @Override
              public void onComplete() {
                connLatch.countDown();
              }
            });
    assert connLatch.await(10, TimeUnit.SECONDS);
    Connection connection = connectionRef.get();
    assert connection != null;
    TestStatement statement = (TestStatement) connection.createStatement("INVALID SQL");
    statement.setShouldFail(true);
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> caughtError = new AtomicReference<>();

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          publisher.subscribe(errorCapturingSubscriber(latch, caughtError));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);
    assert caughtError.get() != null;

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("postgresql\\.query"))
                .resourceName(csEquals("INVALID SQL"))
                .type("sql")
                .error()
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("postgresql")),
                    tag(DB_OPERATION, matches("INVALID")),
                    tag(DB_INSTANCE, is("myapp_db")),
                    tag(DB_USER, is("app_user")),
                    tag(PEER_HOSTNAME, is("db.example.com")),
                    tag(PEER_PORT, is(5432)),
                    error(RuntimeException.class, "query execution failed"),
                    tag(DDTags.PEER_SERVICE_SOURCE, any()),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void connectionWithoutMetadataStillCreatesSpansWithoutDbTags() throws Exception {
    TestConnection connection = new TestConnection();
    TestStatement statement = (TestStatement) connection.createStatement("SELECT 1");
    CountDownLatch latch = new CountDownLatch(1);

    runUnderTrace(
        "parent",
        () -> {
          Publisher<? extends Result> publisher = statement.execute();
          publisher.subscribe(completionSubscriber(latch));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);

    assertTraces(
        TraceMatcher.trace(
            span().operationName("parent"),
            span()
                .childOfPrevious()
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("SELECT ?"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    tag(DB_OPERATION, matches("SELECT")),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  @Test
  void multipleStatementExecutionsCreateSeparateSpans() throws Exception {
    TestConnection connection = new TestConnection();
    TestStatement statement1 = (TestStatement) connection.createStatement("SELECT * FROM users");
    TestStatement statement2 =
        (TestStatement) connection.createStatement("INSERT INTO users (name) VALUES ('test')");
    CountDownLatch latch = new CountDownLatch(2);

    runUnderTrace(
        "parent",
        () -> {
          statement1.execute().subscribe(completionSubscriber(latch));
          statement2.execute().subscribe(completionSubscriber(latch));
          return null;
        });
    assert latch.await(10, TimeUnit.SECONDS);

    assertTraces(
        TraceMatcher.trace(
            TraceMatcher.SORT_BY_START_TIME,
            span().operationName("parent"),
            span()
                .childOfIndex(0)
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("SELECT * FROM users"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    tag(DB_OPERATION, matches("SELECT")),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags()),
            span()
                .childOfIndex(0)
                .operationName(Pattern.compile("r2dbc\\.query"))
                .resourceName(csEquals("INSERT INTO users (name) VALUES (?)"))
                .type("sql")
                .tags(
                    tag(COMPONENT, matches("r2dbc-spi")),
                    tag(SPAN_KIND, is(SPAN_KIND_CLIENT)),
                    tag(DB_TYPE, is("r2dbc")),
                    tag(DB_OPERATION, matches("INSERT")),
                    tag(DDTags.DD_SVC_SRC, any()),
                    defaultTags())));
  }

  /** Creates a subscriber that counts down the latch on completion or error. */
  private static Subscriber<Result> completionSubscriber(CountDownLatch latch) {
    return new Subscriber<Result>() {
      @Override
      public void onSubscribe(Subscription s) {
        s.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(Result result) {}

      @Override
      public void onError(Throwable t) {
        latch.countDown();
      }

      @Override
      public void onComplete() {
        latch.countDown();
      }
    };
  }

  /** Creates a subscriber that captures errors and counts down the latch. */
  private static Subscriber<Result> errorCapturingSubscriber(
      CountDownLatch latch, AtomicReference<Throwable> errorRef) {
    return new Subscriber<Result>() {
      @Override
      public void onSubscribe(Subscription s) {
        s.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(Result result) {}

      @Override
      public void onError(Throwable t) {
        errorRef.set(t);
        latch.countDown();
      }

      @Override
      public void onComplete() {
        latch.countDown();
      }
    };
  }
}
