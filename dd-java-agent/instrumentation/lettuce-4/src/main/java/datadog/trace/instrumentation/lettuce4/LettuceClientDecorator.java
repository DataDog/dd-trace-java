package datadog.trace.instrumentation.lettuce4;

import static datadog.trace.instrumentation.lettuce4.InstrumentationPoints.getCommandResourceName;

import com.lambdaworks.redis.RedisURI;
import com.lambdaworks.redis.protocol.RedisCommand;
import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class LettuceClientDecorator extends DBTypeProcessingDatabaseClientDecorator<RedisURI> {

  public static final CharSequence REDIS_CLIENT = UTF8BytesString.create("redis-client");
  public boolean RedisCommandRaw = Config.get().getRedisCommandArgs();

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation("redis"));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service("redis");
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
      setPeerPort(span, connection.getPort());
      span.setTag("db.redis.dbIndex", connection.getDatabase());
    }
    return super.onConnection(span, connection);
  }

  public AgentSpan onCommand(final AgentSpan span, final RedisCommand<?, ?, ?> command) {
    span.setResourceName(
        null == command ? "Redis Command" : getCommandResourceName(command.getType()));
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

  public AgentSpan setArgs(final AgentSpan span,String raw){
    if (RedisCommandRaw){
      span.setTag("redis.command.args",raw);
    }
    return span;
  }
}
