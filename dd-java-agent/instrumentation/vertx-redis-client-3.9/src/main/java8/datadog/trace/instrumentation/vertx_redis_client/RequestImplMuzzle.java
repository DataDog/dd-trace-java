package datadog.trace.instrumentation.vertx_redis_client;

import io.vertx.redis.RedisClient;
import io.vertx.redis.client.Redis;
import net.bytebuddy.asm.Advice;

public class RequestImplMuzzle {
  @Advice.OnMethodEnter // This advice will never be applied
  public static void muzzleCheck() {
    RedisClient.create(null); // removed in 4.0.x
    Redis.createClient(null, "somehost"); // added in 3.9.x
  }
}
