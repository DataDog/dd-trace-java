package datadog.trace.instrumentation.vertx_redis_client;

import io.vertx.redis.client.Redis;
import net.bytebuddy.asm.Advice;

public class RequestImplMuzzle {
  // This advice will never be applied
  @Advice.OnMethodEnter
  public static void muzzleCheck() {
    // added in 3.9.x
    Redis.createClient(null, "somehost");
  }
}
