package datadog.trace.instrumentation.rediscala;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class RediscalaClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<RedisConnectionInfo> {

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");

  public static final RediscalaClientDecorator DECORATE = new RediscalaClientDecorator();

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation("redis"));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service("redis");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"rediscala", "redis"};
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
    return "redis";
  }

  @Override
  protected String dbUser(final RedisConnectionInfo redisConnectionInfo) {
    return null;
  }

  @Override
  protected String dbInstance(final RedisConnectionInfo redisConnectionInfo) {
    return null;
  }

  @Override
  protected String dbHostname(final RedisConnectionInfo redisConnectionInfo) {
    return redisConnectionInfo.host;
  }

  @Override
  public AgentSpan onConnection(final AgentSpan span, final RedisConnectionInfo connection) {
    if (connection != null) {
      setPeerPort(span, connection.port);
      span.setTag("db.redis.dbIndex", connection.dbIndex);
    }
    return super.onConnection(span, connection);
  }
}
