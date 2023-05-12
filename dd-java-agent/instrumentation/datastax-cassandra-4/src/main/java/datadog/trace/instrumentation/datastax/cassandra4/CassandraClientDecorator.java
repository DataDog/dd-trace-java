package datadog.trace.instrumentation.datastax.cassandra4;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.metadata.Node;
import com.datastax.oss.driver.api.core.servererrors.CoordinatorException;
import com.datastax.oss.driver.api.core.session.Session;
import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;

public class CassandraClientDecorator extends DBTypeProcessingDatabaseClientDecorator<Session> {
  private static final String DB_TYPE = "cassandra";
  private static final String SERVICE_NAME =
      SpanNaming.instance()
          .namingSchema()
          .database()
          .service(Config.get().getServiceName(), DB_TYPE);
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(DB_TYPE));
  public static final CharSequence JAVA_CASSANDRA = UTF8BytesString.create("java-cassandra");

  public static final CassandraClientDecorator DECORATE = new CassandraClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"cassandra"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return JAVA_CASSANDRA;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.CASSANDRA;
  }

  @Override
  protected String dbType() {
    return DB_TYPE;
  }

  @Override
  protected String dbUser(final Session session) {
    return null;
  }

  @Override
  protected String dbInstance(final Session session) {
    return session.getKeyspace().map(Objects::toString).orElse(null);
  }

  @Override
  protected String dbHostname(Session session) {
    return null;
  }

  public AgentSpan onResponse(final AgentSpan span, final ResultSet result) {
    if (result != null) {
      return onResponse(span, result.getExecutionInfo().getCoordinator());
    }

    return span;
  }

  public AgentSpan onResponse(final AgentSpan span, final AsyncResultSet result) {
    if (result != null) {
      return onResponse(span, result.getExecutionInfo().getCoordinator());
    }

    return span;
  }

  @Override
  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    super.onError(span, throwable);

    if (throwable instanceof CoordinatorException) {
      onResponse(span, ((CoordinatorException) throwable).getCoordinator());
    }

    return span;
  }

  private AgentSpan onResponse(AgentSpan span, Node coordinator) {
    if (coordinator != null) {
      SocketAddress address = coordinator.getEndPoint().resolve();
      if (address instanceof InetSocketAddress) {
        onPeerConnection(span, (InetSocketAddress) address);
      }
    }

    return span;
  }
}
