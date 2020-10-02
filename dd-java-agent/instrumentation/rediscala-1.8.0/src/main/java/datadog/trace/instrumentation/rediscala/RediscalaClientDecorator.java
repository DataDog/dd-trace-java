package datadog.trace.instrumentation.rediscala;

import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import redis.RedisCommand;
import redis.protocol.RedisReply;

public class RediscalaClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<RedisCommand<? extends RedisReply, ?>> {

  public static final CharSequence REDIS_COMMAND = UTF8BytesString.createConstant("redis.command");

  private static final String SERVICE_NAME = "redis";
  private static final CharSequence COMPONENT_NAME =
      UTF8BytesString.createConstant("redis-command");

  public static final RediscalaClientDecorator DECORATE = new RediscalaClientDecorator();

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
  protected String dbUser(final RedisCommand<? extends RedisReply, ?> session) {
    return null;
  }

  @Override
  protected String dbInstance(final RedisCommand<? extends RedisReply, ?> session) {
    return null;
  }

  @Override
  protected String dbHostname(RedisCommand<? extends RedisReply, ?> redisCommand) {
    return null;
  }
}
