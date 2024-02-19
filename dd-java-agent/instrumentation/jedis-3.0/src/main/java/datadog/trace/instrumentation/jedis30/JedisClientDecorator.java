package datadog.trace.instrumentation.jedis30;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import redis.clients.jedis.commands.ProtocolCommand;

public class JedisClientDecorator extends DBTypeProcessingDatabaseClientDecorator<ProtocolCommand> {
  public static final CharSequence REDIS_COMMAND = UTF8BytesString.create("redis.command");
  public static final JedisClientDecorator DECORATE = new JedisClientDecorator();

  private static final String REDIS = "redis";
  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation(REDIS));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service(REDIS);
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");
  public boolean RedisCommandRaw = Config.get().getRedisCommandArgs();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jedis", "redis"};
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
  protected String dbUser(final ProtocolCommand session) {
    return null;
  }

  @Override
  protected String dbInstance(final ProtocolCommand session) {
    return null;
  }

  @Override
  protected String dbHostname(ProtocolCommand protocolCommand) {
    return null;
  }
  public AgentSpan setRaw(AgentSpan span, String raw) {
    if (RedisCommandRaw){
      span.setTag("redis.command.args",raw);
    }
    return span;
  }
}
