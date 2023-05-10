package datadog.trace.instrumentation.vertx_redis_client;

import datadog.trace.bootstrap.InstrumentationContext;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.SocketAddress;
import io.vertx.redis.RedisClient;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.impl.RedisConnectionImpl;
import net.bytebuddy.asm.Advice;

public class RedisConnectionConstructAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterConstructor(
      @Advice.Argument(3) final NetSocket netSocket, @Advice.This final RedisConnectionImpl thiz) {
    if (netSocket != null && netSocket.remoteAddress() != null) {
      InstrumentationContext.get(RedisConnection.class, SocketAddress.class)
          .put(thiz, netSocket.remoteAddress());
    }
  }

  // Only apply this advice for versions that we instrument 3.9.x
  private static void muzzleCheck() {
    RedisClient.create(null); // removed in 4.0.x
    Redis.createClient(null, "somehost"); // added in 3.9.x
  }
}
