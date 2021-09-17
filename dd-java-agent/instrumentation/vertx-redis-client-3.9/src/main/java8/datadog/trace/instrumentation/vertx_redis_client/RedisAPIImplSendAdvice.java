package datadog.trace.instrumentation.vertx_redis_client;

import datadog.trace.bootstrap.InstrumentationContext;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.redis.RedisClient;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import net.bytebuddy.asm.Advice;

public class RedisAPIImplSendAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterSend(@Advice.This RedisAPI self, @Advice.Return Future future) {
    ResponseHandlerWrapper handler =
        InstrumentationContext.get(RedisAPI.class, ResponseHandlerWrapper.class).get(self);
    if (handler != null && !future.isComplete()) {
      // add the handler only when the future is not already completed
      future.setHandler(handler);
    }
    if (handler != null && future.isComplete()) {
      // check whether the future has completed some time when adding the handler
      // this might lead to executing the handler twice so the handler must be able to deal with it
      handler.handle(((AsyncResult) future.result()));
    }
  }

  // Only apply this advice for versions that we instrument 3.9.x
  private static void muzzleCheck() {
    RedisClient.create(null); // removed in 4.0.x
    Redis.createClient(null, "somehost"); // added in 3.9.x
  }
}
