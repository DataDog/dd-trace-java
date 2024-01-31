package datadog.trace.instrumentation.spymemcached;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(returns(named(MEMCACHED_PACKAGE + ".internal.OperationFuture")))
            /*
            Flush seems to have a bug when listeners may not be always called.
            Also tracing flush is probably of a very limited value.
            */
            .and(not(named("flush"))),
        MemcachedClientInstrumentation.class.getName() + "$AsyncOperationAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(returns(named(MEMCACHED_PACKAGE + ".internal.GetFuture"))),
        MemcachedClientInstrumentation.class.getName() + "$AsyncGetAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(returns(named(MEMCACHED_PACKAGE + ".internal.BulkFuture"))),
        MemcachedClientInstrumentation.class.getName() + "$AsyncBulkAdvice");
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(namedOneOf("incr", "decr")),
        MemcachedClientInstrumentation.class.getName() + "$SyncOperationAdvice");
  }

  public static class AsyncOperationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter() {
      if (CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) > 0) {
        return null;
      }
      return activateSpan(startSpan(MemcacheClientDecorator.OPERATION_NAME));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Origin("#m") final String methodName,
        @Advice.Return final OperationFuture future) {
      if (scope == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(MemcachedClient.class);
      try (final AgentScope toClose = scope) {
        if (future != null) {
          final OperationCompletionListener listener =
              new OperationCompletionListener(scope.span(), methodName);
          future.addListener(listener);
        }
      }
    }
  }

  public static class AsyncGetAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter() {
      if (CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) > 0) {
        return null;
      }
      return activateSpan(startSpan(MemcacheClientDecorator.OPERATION_NAME));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Origin("#m") final String methodName,
        @Advice.Return final GetFuture future) {
      if (scope == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(MemcachedClient.class);
      try (final AgentScope toClose = scope) {
        if (future != null) {
          final GetCompletionListener listener =
              new GetCompletionListener(scope.span(), methodName);
          future.addListener(listener);
        }
      }
    }
  }

  public static class AsyncBulkAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter() {
      if (CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) > 0) {
        return null;
      }
      return activateSpan(startSpan(MemcacheClientDecorator.OPERATION_NAME));
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Origin("#m") final String methodName,
        @Advice.Return final BulkFuture future) {
      if (scope == null) {
        return;
      }
      CallDepthThreadLocalMap.reset(MemcachedClient.class);
      try (final AgentScope toClose = scope) {
        if (future != null) {
          final BulkGetCompletionListener listener =
              new BulkGetCompletionListener(scope.span(), methodName);
          future.addListener(listener);
        }
      }
    }
  }

  public static class SyncOperationAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static SyncCompletionListener methodEnter(@Advice.Origin("#m") final String methodName) {
      if (CallDepthThreadLocalMap.incrementCallDepth(MemcachedClient.class) > 0) {
        return null;
      }
      final AgentSpan span = startSpan(MemcacheClientDecorator.OPERATION_NAME);
      return new SyncCompletionListener(span, methodName);
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

    public static void muzzleCheck(OperationFuture operationFuture) {
      // before 2.10.4 futures are not completing correctly. We stick at this as minimum version
      operationFuture.signalComplete();
    }
  }
}
