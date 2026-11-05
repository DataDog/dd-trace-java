import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.ExecutionInfo
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.cql.Statement
import com.datastax.oss.driver.api.core.session.Session
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.scopediag.ScopeDiagnostics
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.instrumentation.datastax.cassandra4.AsyncResultSetConsumer
import datadog.trace.instrumentation.datastax.cassandra4.TracingSession

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.BiConsumer

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.COMPLETABLE_FUTURE_CALLBACK

class CassandraCallbackExclusionTest extends InstrumentationSpecification {
  def "async request finisher skips capture and preserves application context: completed=#completed outcome=#outcome"() {
    setup:
    def executionInfo = Stub(ExecutionInfo)
    def response = Stub(AsyncResultSet) {
      getExecutionInfo() >> executionInfo
    }
    def cause = new IllegalStateException("query failed")
    def failure = outcome == "success" ? null : outcome == "wrapped" ? new CompletionException(cause) : cause
    def source = new CompletableFuture<AsyncResultSet>()
    if (completed) {
      complete(source, response, failure)
    }
    AgentSpan querySpan = null
    def delegate = Stub(Session) {
      getKeyspace() >> Optional.empty()
      execute(_, Statement.ASYNC) >> {
        querySpan = activeSpan()
        source
      }
    }
    def session = new TracingSession(delegate, "localhost:9042")
    def parent = startSpan("test", "parent")
    def scope = activateSpan(parent)
    def application = new ApplicationCallback()

    when:
    int before = ScopeDiagnostics.report().records().size()
    def result = session.execute(SimpleStatement.newInstance("SELECT * FROM users"), Statement.ASYNC)
    int captures = ScopeDiagnostics.report().records().size() - before
    before = ScopeDiagnostics.report().records().size()
    def downstream = result.whenComplete(application)
    int downstreamCaptures = ScopeDiagnostics.report().records().size() - before
    scope.close()
    scope = null
    complete(source, response, failure)
    try {
      downstream.toCompletableFuture().join()
      assert failure == null
    } catch (CompletionException expected) {
      assert failure != null
      assert expected.cause.is(cause)
    }

    then:
    captures == 0
    exclude(COMPLETABLE_FUTURE_CALLBACK, new AsyncResultSetConsumer(querySpan))
    !exclude(COMPLETABLE_FUTURE_CALLBACK, application)
    downstreamCaptures == (completed ? 0 : 1)
    application.calls == 1
    application.parent.is(parent)
    querySpan.isError() == (failure != null)
    if (failure == null) {
      assert result.toCompletableFuture().join().is(response)
    } else {
      assert querySpan.getTag("error.type") == IllegalStateException.name
    }

    cleanup:
    scope?.close()
    complete(source, response, failure)
    parent.finish()
    TEST_WRITER.waitForTraces(1)
    assert TEST_WRITER[0].size() == 2

    where:
    completed | outcome
    false     | "success"
    false     | "failure"
    false     | "wrapped"
    true      | "success"
    true      | "failure"
    true      | "wrapped"
  }

  private static void complete(CompletableFuture source, AsyncResultSet response, Throwable failure) {
    if (failure == null) {
      source.complete(response)
    } else {
      source.completeExceptionally(failure)
    }
  }

  private static class ApplicationCallback implements BiConsumer<AsyncResultSet, Throwable> {
    int calls
    AgentSpan parent

    @Override
    void accept(AsyncResultSet result, Throwable failure) {
      calls++
      parent = activeSpan()
    }
  }
}
