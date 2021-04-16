package datadog.trace.instrumentation.vertx_redis_client;

import static datadog.trace.instrumentation.vertx_redis_client.VertxRedisClientDecorator.DECORATE;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.redis.RedisClient;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

public class RedisAPICallAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void beforeCall(
      @Advice.Origin final Method currentMethod,
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
      return;
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
      return;
    }

    final AgentSpan span = DECORATE.startAndDecorateSpan(method.getName());
    handler = new ResponseHandlerWrapper(handler, span);

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
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void afterCall(@Advice.Thrown final Throwable throwable) {
    CallDepthThreadLocalMap.decrementCallDepth(RedisAPI.class);
  }

  // Only apply this advice for versions that we instrument 3.9.x
  private static void muzzleCheck() {
    RedisClient.create(null); // removed in 4.0.x
    Redis.createClient(null, "somehost"); // added in 3.9.x
  }
}
