package datadog.trace.instrumentation.postgresql;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;

public class PostgreSQLDecorator extends DBTypeProcessingDatabaseClientDecorator<DBInfo> {

  public static final PostgreSQLDecorator DECORATE = new PostgreSQLDecorator();

  public static final CharSequence JAVA_POSTGRESQL = UTF8BytesString.create("java-postgresql");
  public static final CharSequence POSTGRESQL_QUERY =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().database().operation("postgresql"));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("postgresql");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"postgresql"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return JAVA_POSTGRESQL;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SQL;
  }

  @Override
  protected String dbType() {
    return "postgresql";
  }

  @Override
  protected String dbUser(final DBInfo info) {
    return info.getUser();
  }

  @Override
  protected String dbInstance(final DBInfo info) {
    if (info.getInstance() != null) {
      return info.getInstance();
    }
    return info.getDb();
  }

  @Override
  protected CharSequence dbHostname(final DBInfo info) {
    return info.getHost();
  }

  @Override
  protected void postProcessServiceAndOperationName(AgentSpan span, NamingEntry namingEntry) {
    if (namingEntry.getService() != null) {
      span.setServiceName(namingEntry.getService(), component());
    }
    span.setOperationName(namingEntry.getOperation());
  }
}
