package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;
import io.vertx.redis.client.Command;

public class VertxRedisClientDecorator extends DBTypeProcessingDatabaseClientDecorator<Object> {

  public static final VertxRedisClientDecorator DECORATE = new VertxRedisClientDecorator();

  public static final CharSequence REDIS_COMMAND = UTF8BytesString.create("redis.command");

  private static final String SERVICE_NAME = "redis";
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
  protected String dbUser(final Object session) {
    return null;
  }

  @Override
  protected String dbInstance(final Object session) {
    return null;
  }

  @Override
  protected String dbHostname(final Object redisCommand) {
    return null;
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
