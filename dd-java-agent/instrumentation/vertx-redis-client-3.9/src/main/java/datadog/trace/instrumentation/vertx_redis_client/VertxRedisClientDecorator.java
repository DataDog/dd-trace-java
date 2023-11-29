package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import io.vertx.core.net.SocketAddress;
import io.vertx.redis.client.Command;

public class VertxRedisClientDecorator
    extends DBTypeProcessingDatabaseClientDecorator<SocketAddress> {

  public static final VertxRedisClientDecorator DECORATE = new VertxRedisClientDecorator();

  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service("redis");
  public static final CharSequence REDIS_COMMAND =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().cache().operation("redis"));

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("redis-command");

  // There are 201 possible Redis commands
  private final DDCache<String, UTF8BytesString> commandCache =
      DDCaches.newFixedSizeCache(201 * 4 / 3);

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"vertx", "vertx-redis-client", "redis"};
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
  protected String dbUser(final SocketAddress socketAddress) {
    return null;
  }

  @Override
  protected String dbInstance(final SocketAddress socketAddress) {
    return null;
  }

  @Override
  protected String dbHostname(final SocketAddress socketAddress) {
    return socketAddress.host();
  }

  public AgentSpan startAndDecorateSpan(String command) {
    UTF8BytesString upperCase =
        commandCache.computeIfAbsent(command, key -> UTF8BytesString.create(key.toUpperCase()));
    return innerStartAndDecorateSpan(upperCase);
  }

  public AgentSpan startAndDecorateSpan(
      Command command, ContextStore<Command, UTF8BytesString> contextStore) {
    return innerStartAndDecorateSpan(contextStore.get(command));
  }

  private AgentSpan innerStartAndDecorateSpan(UTF8BytesString command) {
    final AgentSpan span = startSpan(REDIS_COMMAND);
    DECORATE.afterStart(span);
    DECORATE.onStatement(span, command);
    return span;
  }
}
