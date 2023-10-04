package datadog.trace.instrumentation.lettuce5;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import io.lettuce.core.RedisURI;
import io.lettuce.core.protocol.RedisCommand;

public class LettuceClientDecorator extends DBTypeProcessingDatabaseClientDecorator<RedisURI> {
  public static final CharSequence REDIS_CLIENT = UTF8BytesString.create("redis-client");
  public static final LettuceClientDecorator DECORATE = new LettuceClientDecorator();
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation("redis"));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service("redis");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"lettuce"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return REDIS_CLIENT;
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
  protected String dbUser(final RedisURI connection) {
    return null;
  }

  @Override
  protected String dbInstance(final RedisURI connection) {
    return null;
  }

  @Override
  protected String dbHostname(RedisURI redisURI) {
    return redisURI.getHost();
  }

  @Override
  public AgentSpan onConnection(final AgentSpan span, final RedisURI connection) {
    if (connection != null) {
      setPeerPort(span, connection.getPort());

      span.setTag("db.redis.dbIndex", connection.getDatabase());
    }
    return super.onConnection(span, connection);
  }

  public AgentSpan onCommand(final AgentSpan span, final RedisCommand command) {
    final String commandName = LettuceInstrumentationUtil.getCommandName(command);
    span.setResourceName(LettuceInstrumentationUtil.getCommandResourceName(commandName));
    return span;
  }

  public String resourceNameForConnection(final RedisURI redisURI) {
    return "CONNECT:"
        + redisURI.getHost()
        + ":"
        + redisURI.getPort()
        + "/"
        + redisURI.getDatabase();
  }
}
