package datadog.trace.instrumentation.lettuce4;

import static datadog.trace.instrumentation.lettuce4.InstrumentationPoints.getCommandResourceName;

import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.protocol.RedisCommand;
import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class LettuceClientDecorator extends DBTypeProcessingDatabaseClientDecorator<RedisURI> {

  public static final CharSequence REDIS_CLIENT = UTF8BytesString.create("redis-client");

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation("redis"));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service(Config.get().getServiceName(), "redis");
  public static final LettuceClientDecorator DECORATE = new LettuceClientDecorator();

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
      span.setTag(Tags.PEER_HOSTNAME, connection.getHost());
      setPeerPort(span, connection.getPort());
      span.setTag("db.redis.dbIndex", connection.getDatabase());
      span.setResourceName(resourceName(connection));
    }
    return super.onConnection(span, connection);
  }

  @Override
  protected void postProcessServiceAndOperationName(AgentSpan span, String dbType) {}

  public AgentSpan onCommand(final AgentSpan span, final RedisCommand<?, ?, ?> command) {
    span.setResourceName(
        null == command ? "Redis Command" : getCommandResourceName(command.getType()));
    return span;
  }

  private static String resourceName(final RedisURI connection) {
    return "CONNECT:"
        + connection.getHost()
        + ":"
        + connection.getPort()
        + "/"
        + connection.getDatabase();
  }
}
