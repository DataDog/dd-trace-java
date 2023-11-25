package datadog.trace.instrumentation.vertx_redis_client_4;

import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.impl.RedisStandaloneConnection;
import net.bytebuddy.asm.Advice;

public class RequestImplMuzzle {
  @Advice.OnMethodEnter // This advice will never be applied
  // Limit ourselves to 4.x by using for the RedisStandaloneConnection class that was added in 4.x
  private static void muzzleCheck(RedisStandaloneConnection connection) {
    connection.send(Request.cmd(Command.PING)); // added in 4.x
  }
}
