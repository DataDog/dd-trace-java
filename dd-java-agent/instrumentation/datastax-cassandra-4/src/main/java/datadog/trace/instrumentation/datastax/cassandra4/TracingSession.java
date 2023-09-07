package datadog.trace.instrumentation.datastax.cassandra4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.datastax.cassandra4.CassandraClientDecorator.DECORATE;
import static datadog.trace.instrumentation.datastax.cassandra4.CassandraClientDecorator.OPERATION_NAME;
import static datadog.trace.util.AgentThreadFactory.AgentThread.TRACE_CASSANDRA_ASYNC_SESSION;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.cql.Statement;
import com.datastax.oss.driver.api.core.session.Request;
import com.datastax.oss.driver.api.core.session.Session;
import com.datastax.oss.driver.api.core.type.reflect.GenericType;
import com.datastax.oss.driver.internal.core.session.SessionWrapper;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.util.AgentThreadFactory;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nonnull;

public class TracingSession extends SessionWrapper implements CqlSession {
  private static final ExecutorService EXECUTOR_SERVICE =
      Executors.newCachedThreadPool(new AgentThreadFactory(TRACE_CASSANDRA_ASYNC_SESSION));

  private final String contactPoints;

  public TracingSession(final Session session, final String contactPoints) {
    super(session);
    this.contactPoints = contactPoints;
  }

  @Override
  @Nullable
  public <RequestT extends Request, ResultT> ResultT execute(
      @Nonnull RequestT request, @Nonnull GenericType<ResultT> resultType) {

    if (request instanceof Statement && resultType.equals(Statement.SYNC)) {
      return (ResultT) wrapSyncRequest((Statement) request);
    } else if (request instanceof Statement && resultType.equals(Statement.ASYNC)) {
      return (ResultT) wrapAsyncRequest((Statement) request);
    } else {
      // PrepareRequest or unknown request: just forward to delegate
      return getDelegate().execute(request, resultType);
    }
  }

  private ResultSet wrapSyncRequest(Statement request) {
    AgentSpan span = startSpan(OPERATION_NAME);

    DECORATE.afterStart(span);
    DECORATE.onConnection(span, getDelegate());
    DECORATE.onStatement(span, getQuery(request));
    span.setTag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, contactPoints);

    try (AgentScope scope = activateSpan(span)) {
      ResultSet resultSet = getDelegate().execute(request, Statement.SYNC);
      DECORATE.onResponse(span, resultSet);
      DECORATE.beforeFinish(span);

      return resultSet;
    } catch (Exception e) {
      DECORATE.onError(span, e);
      DECORATE.beforeFinish(span);

      throw e;
    } finally {
      span.finish();
    }
  }

  private CompletionStage<AsyncResultSet> wrapAsyncRequest(Statement request) {
    AgentSpan span = startSpan(OPERATION_NAME);

    DECORATE.afterStart(span);
    DECORATE.onConnection(span, getDelegate());
    DECORATE.onStatement(span, getQuery(request));
    span.setTag(InstrumentationTags.CASSANDRA_CONTACT_POINTS, contactPoints);

    try (AgentScope scope = activateSpan(span)) {
      CompletionStage<AsyncResultSet> completionStage =
          getDelegate().execute(request, Statement.ASYNC);

      return completionStage.whenComplete(
          (result, throwable) -> {
            if (result != null) {
              DECORATE.onResponse(span, result);
            }

            if (throwable instanceof CompletionException) {
              throwable = throwable.getCause();
            }
            DECORATE.onError(span, throwable);
            span.finish();
          });
    }
  }

  private static String getQuery(final Statement statement) {
    String query = null;
    if (statement instanceof BoundStatement) {
      query = ((BoundStatement) statement).getPreparedStatement().getQuery();
    } else if (statement instanceof SimpleStatement) {
      query = ((SimpleStatement) statement).getQuery();
    }

    return query == null ? "" : query;
  }
}
