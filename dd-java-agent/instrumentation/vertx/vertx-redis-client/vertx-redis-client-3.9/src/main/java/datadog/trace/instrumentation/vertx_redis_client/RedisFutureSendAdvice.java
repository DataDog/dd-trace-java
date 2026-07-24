package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.DECORATE;
import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.REDIS_COMMAND;

import datadog.context.ContextContinuation;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.net.SocketAddress;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import io.vertx.redis.client.impl.RequestImpl;
import net.bytebuddy.asm.Advice;

public class RedisFutureSendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope beforeSend(
      @Advice.Argument(value = 0, readOnly = false) Request request,
      @Advice.Local("ddParentContinuation") ContextContinuation parentContinuation)
      throws Throwable {
    // If we had already wrapped the innermost handler in the RedisAPI call, then we should
    // not wrap it again here. See comment in RedisAPICallAdvice
    boolean nested = CallDepthThreadLocalMap.incrementCallDepth(RedisAPI.class) > 0;

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

    // Mark the request handled even when nested, so a later async re-send of the same
    // Request (e.g. via a pooled connection) isn't mistaken for a brand-new command.
    if (nested) {
      return null;
    }

    AgentSpan parentSpan = activeSpan();

    if (parentSpan != null && REDIS_COMMAND.equals(parentSpan.getOperationName())) {
      // FIXME: this is not the best way to do it but in 4.5.0 there can be race conditions
      return null;
    }

    parentContinuation = null == parentSpan ? captureSpan(noopSpan()) : captureSpan(parentSpan);

    final AgentSpan clientSpan =
        DECORATE.startAndDecorateSpan(
            request.command(), InstrumentationContext.get(Command.class, UTF8BytesString.class));

    return activateSpan(clientSpan);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterSend(
      @Advice.Return(readOnly = false) Future<Response> responseFuture,
      @Advice.Local("ddParentContinuation") ContextContinuation parentContinuation,
      @Advice.Enter final AgentScope clientScope,
      @Advice.This final Object thiz) {
    CallDepthThreadLocalMap.decrementCallDepth(RedisAPI.class);
    if (thiz instanceof RedisConnection) {
      final SocketAddress socketAddress =
          InstrumentationContext.get(RedisConnection.class, SocketAddress.class)
              .get((RedisConnection) thiz);
      final AgentSpan span = clientScope != null ? clientScope.span() : activeSpan();
      // Verify the activeSpan() fallback is actually a REDIS_COMMAND span
      if (socketAddress != null && span != null && REDIS_COMMAND.equals(span.getOperationName())) {
        DECORATE.onConnection(span, socketAddress);
        DECORATE.setPeerPort(span, socketAddress.port());
      }
    }
    if (clientScope != null) {
      Promise<Response> promise = Promise.promise();
      responseFuture.onComplete(
          new ResponseHandler(promise, clientScope.span(), parentContinuation));
      responseFuture = promise.future();
      clientScope.close();
    }
  }
}
