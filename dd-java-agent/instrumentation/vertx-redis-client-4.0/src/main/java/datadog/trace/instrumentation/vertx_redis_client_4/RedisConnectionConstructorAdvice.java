package datadog.trace.instrumentation.vertx_redis_client_4;

import datadog.trace.bootstrap.InstrumentationContext;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.impl.RedisStandaloneConnection;
import net.bytebuddy.asm.Advice;

public class RedisConnectionConstructorAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.Argument(3) final NetSocket netSocket,
      @Advice.This final RedisStandaloneConnection thiz) {
    if (netSocket != null && netSocket.remoteAddress() != null) {
      InstrumentationContext.get(RedisConnection.class, SocketAddress.class)
          .put(thiz, netSocket.remoteAddress());
    }
  }

  // Limit ourselves to 4.x by using for the RedisStandaloneConnection class that was added in 4.x
  private static void muzzleCheck(RedisStandaloneConnection connection) {
    connection.send(Request.cmd(Command.PING)); // added in 4.x
  }
}
