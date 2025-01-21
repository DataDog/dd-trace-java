package io.valkey;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class ValkeyClientDecorator extends DBTypeProcessingDatabaseClientDecorator<Connection> {
  public static final ValkeyClientDecorator DECORATE = new ValkeyClientDecorator();

  private static final String VALKEY = "valkey";
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation(VALKEY));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service(VALKEY);
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("valkey-command");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"valkey", VALKEY};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.VALKEY;
  }

  @Override
  protected String dbType() {
    return VALKEY;
  }

  @Override
  protected String dbUser(final Connection connection) {
    return null;
  }

  @Override
  protected String dbInstance(final Connection connection) {
    return null;
  }

  @Override
  protected String dbHostname(Connection connection) {
    return connection.getHostAndPort().getHost();
  }
}
