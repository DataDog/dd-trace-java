package datadog.trace.instrumentation.vertx_redis_client_4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.instrumentation.vertx_redis_client_4.VertxRedisClientDecorator.DECORATE;

import datadog.trace.api.Pair;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import io.vertx.core.Future;
import io.vertx.core.net.SocketAddress;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.RedisStandaloneConnection;
import io.vertx.redis.client.impl.RequestImpl;
import net.bytebuddy.asm.Advice;

public class RedisSendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beforeSend(@Advice.Argument(value = 0, readOnly = false) Request request)
      throws Throwable {
    DECORATE.logging("Request", request);
    ContextStore<Request, Pair> ctxt = InstrumentationContext.get(Request.class, Pair.class);
    Pair<Boolean, AgentScope.Continuation> pair = ctxt.get(request);
    if (pair != null && pair.hasLeft() && pair.getLeft() != null && pair.getLeft()) {
      return null;
    }
    // Create a shallow copy of the Request here to make sure that reused Requests get spans
    if (request instanceof Cloneable) {
      // Other library code do this downcast, so we can do it as well
      request = (Request) ((RequestImpl) request).clone();
    }
    ctxt.put(request, Pair.of(Boolean.TRUE, null));
    // If we had already wrapped the innermost handler in the RedisAPI call, then we should
    // not wrap it again here. See comment in RedisAPICallAdvice
    if (CallDepthThreadLocalMap.incrementCallDepth(RedisAPI.class) > 0) {
      return AgentTracer.NoopAgentScope.INSTANCE;
    }

    AgentSpan parentSpan = activeSpan();
    DECORATE.logging("Parent span", parentSpan);
    AgentScope.Continuation parentContinuation =
        parentSpan == null ? null : captureSpan(parentSpan);
    ctxt.put(request, Pair.of(Boolean.TRUE, parentContinuation));
    final AgentSpan clientSpan =
        DECORATE.startAndDecorateSpan(
            request.command(), InstrumentationContext.get(Command.class, UTF8BytesString.class));
    return activateSpan(clientSpan, true);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterSend(
      @Advice.Argument(value = 0, readOnly = false) Request request,
      @Advice.Return Future<Response> responseFuture,
      @Advice.Enter final AgentScope clientScope,
      @Advice.This final Object thiz) {
    Pair<Boolean, AgentScope.Continuation> pair =
        InstrumentationContext.get(Request.class, Pair.class).get(request);
    DECORATE.logging("Parent continuation", pair.getRight());
    if (clientScope != null) {
      responseFuture.onComplete(new ResponseHandlerWrapper(clientScope.span(), pair.getRight()));
    }
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
    if (clientScope != null) {
      CallDepthThreadLocalMap.decrementCallDepth(RedisAPI.class);
      clientScope.close();
    }
  }

  // Limit ourselves to 4.x by using for the RedisStandaloneConnection class that was added in 4.x
  private static void muzzleCheck(RedisStandaloneConnection connection) {
    connection.close(); // added in 4.x
  }
}
