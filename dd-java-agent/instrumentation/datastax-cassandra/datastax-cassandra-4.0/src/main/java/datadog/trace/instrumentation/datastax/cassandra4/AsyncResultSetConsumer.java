package datadog.trace.instrumentation.datastax.cassandra4;

import static datadog.trace.instrumentation.datastax.cassandra4.CassandraClientDecorator.DECORATE;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;

public final class AsyncResultSetConsumer implements BiConsumer<AsyncResultSet, Throwable> {
  private final AgentSpan span;

  public AsyncResultSetConsumer(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void accept(AsyncResultSet result, Throwable throwable) {
    if (result != null) {
      DECORATE.onResponse(span, result);
    }
    if (throwable instanceof CompletionException) {
      throwable = throwable.getCause();
    }
    DECORATE.onError(span, throwable);
    span.finish();
  }
}
