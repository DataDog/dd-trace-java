package datadog.trace.instrumentation.lettuce4;

import static datadog.trace.instrumentation.lettuce4.InstrumentationPoints.getCommandResourceName;

import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.protocol.RedisCommand;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class LettuceClientDecorator extends DBTypeProcessingDatabaseClientDecorator<RedisURI> {

  public static final CharSequence REDIS_QUERY = UTF8BytesString.createConstant("redis.query");

  public static final LettuceClientDecorator DECORATE = new LettuceClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"lettuce"};
  }

  @Override
  protected String service() {
    return "redis";
  }

  @Override
  protected String component() {
    return "redis-client";
  }

  @Override
  protected String spanType() {
    return DDSpanTypes.REDIS;
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
  public AgentSpan onConnection(final AgentSpan span, final RedisURI connection) {
    if (connection != null) {
      span.setTag(Tags.PEER_HOSTNAME, connection.getHost());
      span.setTag(Tags.PEER_PORT, connection.getPort());
      span.setTag("db.redis.dbIndex", connection.getDatabase());
      span.setTag(DDTags.RESOURCE_NAME, resourceName(connection));
    }
    return super.onConnection(span, connection);
  }

  public AgentSpan onCommand(final AgentSpan span, final RedisCommand<?, ?, ?> command) {
    span.setTag(
        DDTags.RESOURCE_NAME,
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
