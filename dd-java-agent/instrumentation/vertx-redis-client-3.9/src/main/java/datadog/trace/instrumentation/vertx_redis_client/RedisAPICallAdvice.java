package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.captureSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.DECORATE;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class RedisAPICallAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static boolean beforeCall(
      @Advice.Origin final Method currentMethod,
      @Advice.This final RedisAPI self,
      @Advice.Local("callScope") AgentScope scope,
      @Advice.Argument(
              value = 0,
              readOnly = false,
              optional = true,
              typing = Assigner.Typing.DYNAMIC)
          Object arg1,
      @Advice.Argument(
              value = 1,
              readOnly = false,
              optional = true,
              typing = Assigner.Typing.DYNAMIC)
          Object arg2,
      @Advice.Argument(
              value = 2,
              readOnly = false,
              optional = true,
              typing = Assigner.Typing.DYNAMIC)
          Object arg3,
      @Advice.Argument(
              value = 3,
              readOnly = false,
              optional = true,
              typing = Assigner.Typing.DYNAMIC)
          Object arg4,
      @Advice.Argument(
              value = 4,
              readOnly = false,
              optional = true,
              typing = Assigner.Typing.DYNAMIC)
          Object arg5) {
    // This API calls the underlying Redis.send or RedisConnection.send with a newly created
    // Request (where we would actually like to add the information that this has already
    // been handled) and a new Future as handler (so we can't look at the handler itself
    // either), so this seems to be the only way to communicate that we have already wrapped
    // the handler. :(
    if (CallDepthThreadLocalMap.incrementCallDepth(RedisAPI.class) > 0) {
      return true;
    }

    // TODO what is the recreated for every read about in the @Advice.Origin javadoc?
    Method method = currentMethod;
    int position = method.getParameterCount();
    Handler<AsyncResult<Response>> handler = null;

    switch (position) {
      case 1:
        if (arg1 instanceof Handler) {
          handler = (Handler<AsyncResult<Response>>) arg1;
        }
        break;
      case 2:
        if (arg2 instanceof Handler) {
          handler = (Handler<AsyncResult<Response>>) arg2;
        }
        break;
      case 3:
        if (arg3 instanceof Handler) {
          handler = (Handler<AsyncResult<Response>>) arg3;
        }
        break;
      case 4:
        if (arg4 instanceof Handler) {
          handler = (Handler<AsyncResult<Response>>) arg4;
        }
        break;
      case 5:
        if (arg5 instanceof Handler) {
          handler = (Handler<AsyncResult<Response>>) arg5;
        }
        break;
      default:
    }

    if (null == handler || handler instanceof ResponseHandlerWrapper) {
      return true;
    }

    final AgentSpan parentSpan = activeSpan();
    final AgentSpan clientSpan = DECORATE.startAndDecorateSpan(method.getName());
    AgentScope.Continuation parentContinuation =
        null == parentSpan ? captureSpan(noopSpan()) : captureSpan(parentSpan);
    /*
    Opens a new scope.
    The potential racy condition when the handler may be added to an already finished task is handled
    by RedisAPIImplSendAdvice.
    */
    scope = activateSpan(clientSpan);
    ResponseHandlerWrapper respHandler =
        new ResponseHandlerWrapper(handler, clientSpan, parentContinuation);
    handler = respHandler;

    switch (position) {
      case 1:
        arg1 = handler;
        break;
      case 2:
        arg2 = handler;
        break;
      case 3:
        arg3 = handler;
        break;
      case 4:
        arg4 = handler;
        break;
      case 5:
        arg5 = handler;
        break;
      default:
    }
    /*
    Store the response handler in the context so that it can be retrieved in RedisAPIImplSendAdvice
    */
    InstrumentationContext.get(RedisAPI.class, ResponseHandlerWrapper.class).put(self, respHandler);
    return true;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterCall(
      @Advice.Thrown final Throwable throwable,
      @Advice.This final RedisAPI self,
      @Advice.Local("callScope") AgentScope scope,
      @Advice.Enter final boolean decrement) {
    if (decrement) {
      CallDepthThreadLocalMap.decrementCallDepth(RedisAPI.class);
    }

    scope.close();

    // Clean the response handler from the context
    InstrumentationContext.get(RedisAPI.class, ResponseHandlerWrapper.class).put(self, null);
  }

  // Only apply this advice for versions that we instrument 3.9.x
  private static void muzzleCheck() {
    Redis.createClient(null, "somehost"); // added in 3.9.x
  }
}
