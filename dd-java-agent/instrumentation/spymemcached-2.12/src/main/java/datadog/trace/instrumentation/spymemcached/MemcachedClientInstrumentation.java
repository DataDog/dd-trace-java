package datadog.trace.instrumentation.spymemcached;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.internal.BulkFuture;
import net.spy.memcached.internal.GetFuture;
import net.spy.memcached.internal.OperationFuture;

@AutoService(Instrumenter.class)
public final class MemcachedClientInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  private static final String MEMCACHED_PACKAGE = "net.spy.memcached";

  public MemcachedClientInstrumentation() {
    super("spymemcached");
  }

  @Override
  public String instrumentedType() {
    return MEMCACHED_PACKAGE + ".MemcachedClient";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MemcacheClientDecorator",
      packageName + ".CompletionListener",
      packageName + ".SyncCompletionListener",
      packageName + ".GetCompletionListener",
      packageName + ".OperationCompletionListener",
      packageName + ".BulkGetCompletionListener"
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(returns(named(MEMCACHED_PACKAGE + ".internal.OperationFuture")))
            /*
            Flush seems to have a bug when listeners may not be always called.
            Also tracing flush is probably of a very limited value.
            */
            .and(not(named("flush"))),
        MemcachedClientInstrumentation.class.getName() + "$AsyncOperationAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(returns(named(MEMCACHED_PACKAGE + ".internal.GetFuture"))),
        MemcachedClientInstrumentation.class.getName() + "$AsyncGetAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(returns(named(MEMCACHED_PACKAGE + ".internal.BulkFuture"))),
        MemcachedClientInstrumentation.class.getName() + "$AsyncBulkAdvice");
    transformation.applyAdvice(
        isMethod().and(isPublic()).and(namedOneOf("incr", "decr")),
        MemcachedClientInstrumentation.class.getName() + "$SyncOperationAdvice");
  }

  public static class AsyncOperationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean methodEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) <= 0;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final boolean shouldInjectListener,
        @Advice.This final MemcachedClient client,
        @Advice.Origin("#m") final String methodName,
        @Advice.Return final OperationFuture future) {
      if (!shouldInjectListener) {
        return;
      }
      CallDepthThreadLocalMap.reset(MemcachedClient.class);
      if (future != null) {
        final OperationCompletionListener listener =
            new OperationCompletionListener(client.getConnection(), methodName);
        future.addListener(listener);
      }
    }
  }

  public static class AsyncGetAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean methodEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) <= 0;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final boolean shouldInjectListener,
        @Advice.This final MemcachedClient client,
        @Advice.Origin("#m") final String methodName,
        @Advice.Return final GetFuture future) {
      if (!shouldInjectListener) {
        return;
      }
      CallDepthThreadLocalMap.reset(MemcachedClient.class);
      if (future != null) {
        final GetCompletionListener listener =
            new GetCompletionListener(client.getConnection(), methodName);
        future.addListener(listener);
      }
    }
  }

  public static class AsyncBulkAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean methodEnter() {
      return CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) <= 0;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final boolean shouldInjectListener,
        @Advice.This final MemcachedClient client,
        @Advice.Origin("#m") final String methodName,
        @Advice.Return final BulkFuture future) {
      if (!shouldInjectListener) {
        return;
      }
      CallDepthThreadLocalMap.reset(MemcachedClient.class);
      if (future != null) {
        final BulkGetCompletionListener listener =
            new BulkGetCompletionListener(client.getConnection(), methodName);
        future.addListener(listener);
      }
    }
  }

  public static class SyncOperationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SyncCompletionListener methodEnter(
        @Advice.This final MemcachedClient client, @Advice.Origin("#m") final String methodName) {
      if (CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) <= 0) {
        return new SyncCompletionListener(client.getConnection(), methodName);
      } else {
        return null;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final SyncCompletionListener listener,
        @Advice.Thrown final Throwable thrown) {
      if (listener == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(MemcachedClient.class);
      listener.done(thrown);
    }
  }
}
