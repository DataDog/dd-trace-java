package datadog.trace.instrumentation.vertx_redis_client;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import io.vertx.redis.RedisClient;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.impl.CommandImpl;
import net.bytebuddy.asm.Advice;

public class CommandImplConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final CommandImpl zis, @Advice.Argument(0) String command) {
    InstrumentationContext.get(Command.class, UTF8BytesString.class)
        .put(zis, UTF8BytesString.create(command.toUpperCase()));
  }

  // Only apply this advice for versions that we instrument 3.9.x
  private static void muzzleCheck() {
    RedisClient.create(null); // removed in 4.0.x
    Redis.createClient(null, "somehost"); // added in 3.9.x
  }
}
