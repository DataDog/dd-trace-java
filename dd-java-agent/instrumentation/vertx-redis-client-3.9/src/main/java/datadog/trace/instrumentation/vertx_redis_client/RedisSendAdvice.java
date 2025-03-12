package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.DECORATE;
import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.REDIS_COMMAND;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.net.SocketAddress;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.RequestImpl;
import net.bytebuddy.asm.Advice;

public class RedisSendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beforeSend(
      @Advice.Argument(value = 0, readOnly = false) Request request,
      @Advice.Argument(value = 1, readOnly = false) Handler<AsyncResult<Response>> handler)
      throws Throwable {
    if (null == handler || handler instanceof ResponseHandlerWrapper) {
      return null;
    }

    ContextStore<Request, Boolean> ctxt = InstrumentationContext.get(Request.class, Boolean.class);
    Boolean handled = ctxt.get(request);
    if (null != handled && handled) {
      return null;
    }
    // Create a shallow copy of the Request here to make sure that reused Requests get spans
    if (request instanceof Cloneable) {
      // Other library code do this downcast, so we can do it as well
      request = (Request) ((RequestImpl) request).clone();
    }
    ctxt.put(request, Boolean.TRUE);

    // If we had already wrapped the innermost handler in the RedisAPI call, then we should
    // not wrap it again here. See comment in RedisAPICallAdvice
    if (CallDepthThreadLocalMap.incrementCallDepth(RedisAPI.class) > 0) {
      return noopScope();
    }

    AgentSpan parentSpan = activeSpan();

    if (parentSpan != null && REDIS_COMMAND.equals(parentSpan.getOperationName())) {
      return null;
    }

    AgentScope.Continuation parentContinuation =
        null == parentSpan ? captureSpan(noopSpan()) : captureSpan(parentSpan);
    final AgentSpan clientSpan =
        DECORATE.startAndDecorateSpan(
            request.command(), InstrumentationContext.get(Command.class, UTF8BytesString.class));

    handler = new ResponseHandlerWrapper(handler, clientSpan, parentContinuation);
    return activateSpan(clientSpan);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterSend(
      @Advice.Enter final AgentScope clientScope, @Advice.This final Object thiz) {
    if (thiz instanceof RedisConnection) {
      final SocketAddress socketAddress =
          InstrumentationContext.get(RedisConnection.class, SocketAddress.class)
              .get((RedisConnection) thiz);
      final AgentSpan span = clientScope != null ? clientScope.span() : activeSpan();
      if (socketAddress != null && span != null) {
        DECORATE.onConnection(span, socketAddress);
        DECORATE.setPeerPort(span, socketAddress.port());
      }
    }
    if (null != clientScope) {
      CallDepthThreadLocalMap.decrementCallDepth(RedisAPI.class);
      clientScope.close();
    }
  }

  // Only apply this advice for versions that we instrument 3.9.x
  private static void muzzleCheck() {
    Redis.createClient(null, "somehost"); // added in 3.9.x
  }
}
