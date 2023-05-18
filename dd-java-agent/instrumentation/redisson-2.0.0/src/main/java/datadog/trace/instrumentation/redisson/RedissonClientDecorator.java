package datadog.trace.instrumentation.redisson;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import org.redisson.client.protocol.CommandData;

public class RedissonClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<CommandData<?, ?>> {
  public static final RedissonClientDecorator DECORATE = new RedissonClientDecorator();

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation("redis"));
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service(Config.get().getServiceName(), "redis");

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"redisson", "redis"};
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
  protected String dbUser(CommandData<?, ?> commandData) {
    return null;
  }

  @Override
  protected String dbInstance(CommandData<?, ?> commandData) {
    return null;
  }

  @Override
  protected CharSequence dbHostname(CommandData<?, ?> commandData) {
    return null;
  }
}
