package redis.clients.jedis;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class JedisClientDecorator extends DBTypeProcessingDatabaseClientDecorator<Connection> {
  public static final JedisClientDecorator DECORATE = new JedisClientDecorator();

  private static final String REDIS = "redis";
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation(REDIS));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service(REDIS);
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");

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
  protected String dbHostname(Connection connection) {
    // getHostAndPort is protected hence the decorator sits in the same package
    return connection.getHostAndPort().getHost();
  }
}
