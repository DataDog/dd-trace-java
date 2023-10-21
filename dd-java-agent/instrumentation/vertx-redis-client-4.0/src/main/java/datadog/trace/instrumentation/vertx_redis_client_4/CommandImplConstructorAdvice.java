package datadog.trace.instrumentation.vertx_redis_client_4;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.impl.CommandImpl;
import io.vertx.redis.client.impl.RedisStandaloneConnection;
import net.bytebuddy.asm.Advice;

public class CommandImplConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.This final CommandImpl zis, @Advice.Argument(0) String command) {
    InstrumentationContext.get(Command.class, UTF8BytesString.class)
        .put(zis, UTF8BytesString.create(command.toUpperCase()));
  }

  // Limit ourselves to 4.x by using for the RedisStandaloneConnection class that was added in 4.x
  private static void muzzleCheck(RedisStandaloneConnection connection) {
    connection.close(); // added in 4.x
  }
}
