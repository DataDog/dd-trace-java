package datadog.trace.instrumentation.jedis;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import redis.clients.jedis.Protocol;

public class JedisClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<Protocol.Command> {
  public static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");
  public static final CharSequence REDIS_COMMAND = UTF8BytesString.create("redis.command");

  public boolean RedisCommandRaw = Config.get().getRedisCommandArgs();
  public static final JedisClientDecorator DECORATE = new JedisClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jedis", "redis"};
  }

  @Override
  protected String service() {
    return "redis";
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
  protected String dbUser(final Protocol.Command session) {
    return null;
  }

  @Override
  protected String dbInstance(final Protocol.Command session) {
    return null;
  }

  @Override
  protected String dbHostname(Protocol.Command command) {
    return null;
  }

  public AgentSpan setRaw(AgentSpan span, String raw) {
    if (RedisCommandRaw){
      span.setTag("redis.command.args",raw);
    }
    return span;
  }
}
