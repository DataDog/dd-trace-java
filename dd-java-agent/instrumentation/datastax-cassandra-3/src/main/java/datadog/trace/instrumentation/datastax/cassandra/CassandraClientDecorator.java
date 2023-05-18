package datadog.trace.instrumentation.datastax.cassandra;

import com.datastax.driver.core.Host;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Session;
import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

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
    return session.getLoggedKeyspace();
  }

  @Override
  protected String dbHostname(Session session) {
    // Getting hostname through session.getState() seems to be expensive
    return null;
  }

  public AgentSpan onResponse(final AgentSpan span, final ResultSet result) {
    if (result != null) {
      final Host host = result.getExecutionInfo().getQueriedHost();
      onPeerConnection(span, host.getSocketAddress());
    }
    return span;
  }
}
