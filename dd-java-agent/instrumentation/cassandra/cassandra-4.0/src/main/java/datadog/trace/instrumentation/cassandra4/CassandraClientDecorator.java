package datadog.trace.instrumentation.cassandra4;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.ExecutionInfo;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.metadata.EndPoint;
import com.datastax.oss.driver.api.core.metadata.Node;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.api.normalize.SQLNormalizer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class CassandraClientDecorator extends DBTypeProcessingDatabaseClientDecorator<CqlSession> {
  private static final String DB_TYPE = "cassandra";
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service(DB_TYPE);
  public static final String OPERATION_NAME =
      SpanNaming.instance().namingSchema().database().operation(DB_TYPE);
  public static final String JAVA_CASSANDRA = "java-cassandra";

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
  protected String dbUser(final CqlSession session) {
    return null;
  }

  @Override
  protected String dbInstance(final CqlSession session) {
    return session.getKeyspace().map(k -> k.asCql(false)).orElse(null);
  }

  @Override
  protected String dbHostname(final CqlSession session) {
    return ContactPointsUtil.getFirstHost(session);
  }

  public void onStatement(final AgentSpan span, final CharSequence statement) {
    span.setResourceName(SQLNormalizer.normalize(statement.toString()).toString());
    final CharSequence operation = DBQueryInfo.extractOperation(statement);
    if (operation != null) {
      span.setTag(Tags.DB_OPERATION, operation);
    }
  }

  public void onResponse(final AgentSpan span, final ResultSet result) {
    if (result != null) {
      final ExecutionInfo executionInfo = result.getExecutionInfo();
      if (executionInfo != null) {
        final Node coordinator = executionInfo.getCoordinator();
        if (coordinator != null) {
          final EndPoint endPoint = coordinator.getEndPoint();
          if (endPoint != null) {
            final SocketAddress socketAddress = endPoint.resolve();
            if (socketAddress instanceof InetSocketAddress) {
              onPeerConnection(span, (InetSocketAddress) socketAddress);
            }
          }
        }
      }
    }
  }
}
