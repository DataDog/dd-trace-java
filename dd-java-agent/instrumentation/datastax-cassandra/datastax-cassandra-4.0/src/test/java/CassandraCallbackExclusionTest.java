import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.session.Session;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.scopediag.ScopeDiagnostics;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.datastax.cassandra4.TracingSession;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.tabletest.junit.TableTest;

class CassandraCallbackExclusionTest extends AbstractInstrumentationTest {
  @TableTest({
    "scenario                  | completed | outcome",
    "pending success           | false     | success",
    "pending failure           | false     | failure",
    "pending wrapped failure   | false     | wrapped",
    "completed success         | true      | success",
    "completed failure         | true      | failure",
    "completed wrapped failure | true      | wrapped"
  })
  void asyncRequestFinisherSkipsCaptureAndPreservesApplicationContext(
      boolean completed, String outcome) throws Exception {
    ExecutionInfo executionInfo = mock(ExecutionInfo.class);
    AsyncResultSet response = mock(AsyncResultSet.class);
    when(response.getExecutionInfo()).thenReturn(executionInfo);
    IllegalStateException cause = new IllegalStateException("query failed");
    Throwable failure =
        "success".equals(outcome)
            ? null
            : "wrapped".equals(outcome) ? new CompletionException(cause) : cause;
    CompletableFuture<AsyncResultSet> source = new CompletableFuture<>();
    if (completed) {
      complete(source, response, failure);
    }
    AtomicReference<AgentSpan> querySpan = new AtomicReference<>();
    SimpleStatement statement = SimpleStatement.newInstance("SELECT * FROM users");
    Session delegate = mock(Session.class);
    when(delegate.getKeyspace()).thenReturn(Optional.empty());
    when(delegate.execute(statement, Statement.ASYNC))
        .thenAnswer(invocation -> {
          querySpan.set(activeSpan());
          return source;
        });
    TracingSession session = new TracingSession(delegate, "localhost:9042");
    AgentSpan parent = startSpan("test", "parent");
    ContextScope scope = activateSpan(parent);
    ApplicationCallback application = new ApplicationCallback();

    try {
      int before = captures();
      CompletionStage<AsyncResultSet> result = session.execute(statement, Statement.ASYNC);
      int captures = captures() - before;
      before = captures();
      CompletionStage<AsyncResultSet> downstream = result.whenComplete(application);
      int downstreamCaptures = captures() - before;
      scope.close();
      scope = null;
      complete(source, response, failure);
      if (failure == null) {
        downstream.toCompletableFuture().join();
      } else {
        CompletionException expected =
            assertThrows(CompletionException.class, () -> downstream.toCompletableFuture().join());
        assertSame(cause, expected.getCause());
      }

      assertEquals(0, captures);
      assertEquals(completed ? 0 : 1, downstreamCaptures);
      assertEquals(1, application.calls);
      assertSame(parent, application.parent);
      assertEquals(failure != null, querySpan.get().isError());
      if (failure == null) {
        assertSame(response, result.toCompletableFuture().join());
      } else {
        assertEquals(IllegalStateException.class.getName(), querySpan.get().getTag("error.type"));
      }
    } finally {
      if (scope != null) {
        scope.close();
      }
      complete(source, response, failure);
      parent.finish();
      writer.waitForTraces(1);
      assertEquals(2, writer.get(0).size());
    }
  }

  private static int captures() {
    return ScopeDiagnostics.report().records().size();
  }

  private static void complete(
      CompletableFuture<AsyncResultSet> source, AsyncResultSet response, Throwable failure) {
    if (failure == null) {
      source.complete(response);
    } else {
      source.completeExceptionally(failure);
    }
  }

  private static class ApplicationCallback implements BiConsumer<AsyncResultSet, Throwable> {
    int calls;
    AgentSpan parent;

    @Override
    public void accept(AsyncResultSet result, Throwable failure) {
      calls++;
      parent = activeSpan();
    }
  }
}
