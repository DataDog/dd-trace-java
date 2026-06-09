package datadog.trace.instrumentation.jedis30;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import redis.clients.jedis.Connection;

public class Jedis30ClientDecorator extends DBTypeProcessingDatabaseClientDecorator<Connection> {
  private static final String REDIS = "redis";
  private static final String REDIS_RAW_COMMAND = "redis.raw_command";
  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation(REDIS));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service(REDIS);
  public static final Jedis30ClientDecorator DECORATE = new Jedis30ClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jedis", REDIS};
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
    return InternalSpanTypes.REDIS;
  }

  @Override
  protected String dbType() {
    return REDIS;
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
  protected String dbHostname(final Connection connection) {
    return connection.getHost();
  }

  @Override
  public AgentSpan onStatement(final AgentSpan span, final CharSequence statement) {
    span.setTag(REDIS_RAW_COMMAND, statement.toString());
    return super.onStatement(span, statement);
  }

  @Override
  public AgentSpan onConnection(final AgentSpan span, final Connection connection) {
    if (connection != null) {
      setPeerPort(span, connection.getPort());
    }
    return super.onConnection(span, connection);
  }
}
