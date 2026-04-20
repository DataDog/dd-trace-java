package datadog.trace.instrumentation.ignite.v2.cache;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.ignite.v2.cache.IgniteCacheDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.query.Query;

public final class IgniteCacheSyncInstrumentation extends AbstractIgniteCacheInstrumentation {

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "loadCache",
                    "size",
                    "sizeLong",
                    "invokeAll",
                    "getAll",
                    "getEntries",
                    "getAllOutTx",
                    "containsKeys",
                    "putAll",
                    "removeAll")),
        IgniteCacheSyncInstrumentation.class.getName() + "$IgniteAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "getAndPutIfAbsent",
                    "get",
                    "getEntry",
                    "containsKey",
                    "getAndPut",
                    "put",
                    "putIfAbsent",
                    "remove",
                    "getAndRemove",
                    "replace",
                    "getAndReplace",
                    "clear",
                    "invoke")),
        IgniteCacheSyncInstrumentation.class.getName() + "$KeyedAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("query"))
            .and(
                takesArgument(
                    0,
                    namedOneOf(
                        "org.apache.ignite.cache.query.Query",
                        "org.apache.ignite.cache.query.SqlFieldsQuery"))),
        IgniteCacheSyncInstrumentation.class.getName() + "$QueryAdvice");
  }

  public static class IgniteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final IgniteCache that, @Advice.Origin("#m") final String methodName) {
      // Ensure that we only create a span for the top-level cache method
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(IgniteCache.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(IgniteCacheDecorator.OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onIgnite(
          span, InstrumentationContext.get(IgniteCache.class, Ignite.class).get(that));
      DECORATE.onOperation(span, that.getName(), methodName);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {

      if (scope == null) {
        return;
      }

      try {
        DECORATE.onError(scope.span(), throwable);
        DECORATE.beforeFinish(scope.span());
      } finally {
        scope.close();
        scope.span().finish();
        CallDepthThreadLocalMap.reset(IgniteCache.class); // reset call depth count
      }
    }
  }

  public static class KeyedAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final IgniteCache that,
        @Advice.Origin("#m") final String methodName,
        @Advice.Argument(value = 0, optional = true) final Object key) {
      // Ensure that we only create a span for the top-level cache method
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(IgniteCache.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(IgniteCacheDecorator.OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onIgnite(
          span, InstrumentationContext.get(IgniteCache.class, Ignite.class).get(that));
      DECORATE.onOperation(span, that.getName(), methodName, key);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      try {
        DECORATE.onError(scope.span(), throwable);
        DECORATE.beforeFinish(scope.span());
      } finally {
        scope.close();
        scope.span().finish();
        CallDepthThreadLocalMap.reset(IgniteCache.class); // reset call depth count
      }
    }
  }

  public static class QueryAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final IgniteCache that,
        @Advice.Origin("#m") final String methodName,
        @Advice.Argument(0) final Query query) {
      // Ensure that we only create a span for the top-level cache method
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(IgniteCache.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(IgniteCacheDecorator.OPERATION_NAME);
      DECORATE.afterStart(span);
      DECORATE.onIgnite(
          span, InstrumentationContext.get(IgniteCache.class, Ignite.class).get(that));
      DECORATE.onQuery(span, that.getName(), methodName, query);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      try {
        DECORATE.onError(scope.span(), throwable);
        DECORATE.beforeFinish(scope.span());
      } finally {
        scope.close();
        scope.span().finish();
        CallDepthThreadLocalMap.reset(IgniteCache.class); // reset call depth count
      }
    }
  }
}
