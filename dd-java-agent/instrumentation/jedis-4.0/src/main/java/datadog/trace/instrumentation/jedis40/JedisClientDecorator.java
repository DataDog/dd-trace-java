package datadog.trace.instrumentation.jedis40;

import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import redis.clients.jedis.commands.ProtocolCommand;

public class JedisClientDecorator extends DBTypeProcessingDatabaseClientDecorator<ProtocolCommand> {
  public static final CharSequence REDIS_COMMAND = UTF8BytesString.create("redis.command");
  public static final JedisClientDecorator DECORATE = new JedisClientDecorator();

  private static final String SERVICE_NAME = "redis";
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");

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
    // As the instrumentation is on the Connection object to retrieve the current host
    // should be a matter of calling the getHostAndPort on the instrumented Connection object.
    // Binding a variable to the `this` of the instrumented method by using `@Advice.This`
    // annotation to the onEnter method in JedisInstrumentation should allow this
    // To support this some changes may to how the Decorator is instantiated. Unsure if the host and
    // port
    // can change per thread/invocation.

    return null;
  }
}
