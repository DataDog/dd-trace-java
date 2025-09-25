package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.DECORATE;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import io.vertx.core.Future;
import io.vertx.core.net.SocketAddress;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Response;
import net.bytebuddy.asm.Advice;

public class RedisAPIImplSendAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterSend(
      @Advice.This RedisAPI self,
      @Advice.FieldValue("connection") final RedisConnection connection,
      @Advice.Return Future<Response> future) {
    /*
    Here we can safely set the handler for a command related Future instance.
    We need to take some precautions due to a non-existent operation which would allow setting the handler
    and in case the Future is complete immediately calling it.
     */

    // Note that we should not _leak_ the active scope to the handler if it gets executed directly
    try (AgentScope scope = activateSpan(noopSpan())) {
      // Get the handler from the context, set by RedisAPICallAdvice
      ResponseHandlerWrapper handler =
          InstrumentationContext.get(RedisAPI.class, ResponseHandlerWrapper.class).get(self);
      if (handler != null) {
        if (handler.clientSpan != null && connection != null) {
          final SocketAddress socketAddress =
              InstrumentationContext.get(RedisConnection.class, SocketAddress.class)
                  .get(connection);
          if (socketAddress != null) {
            DECORATE.onConnection(handler.clientSpan, socketAddress);
            DECORATE.setPeerPort(handler.clientSpan, socketAddress.port());
          }
        }
        if (!future.isComplete()) {
          // Add the handler only when the future is not already completed
          future.onComplete(handler);
        } else {
          // Check whether the future has completed some time when adding the handler.
          // This might lead to executing the handler twice so the handler must be able to deal with
          // it.
          handler.handle(future);
        }
      }
    }
  }

  // Only apply this advice for versions that we instrument 3.9.x
  private static void muzzleCheck() {
    Redis.createClient(null, "somehost"); // added in 3.9.x
  }
}
