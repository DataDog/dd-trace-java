package datadog.trace.instrumentation.ignite.v2.cache;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.ignite.v2.cache.IgniteCacheDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.lang.IgniteFuture;

public final class IgniteCacheAsyncInstrumentation extends AbstractIgniteCacheInstrumentation {

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "loadCacheAsync",
                    "sizeAsync",
                    "sizeLongAsync",
                    "invokeAllAsync",
                    "getAllAsync",
                    "getEntriesAsync",
                    "getAllOutTxAsync",
                    "containsKeysAsync",
                    "putAllAsync",
                    "removeAllAsync")),
        IgniteCacheAsyncInstrumentation.class.getName() + "$IgniteAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "getAndPutIfAbsentAsync",
                    "getAsync",
                    "getEntryAsync",
                    "containsKeyAsync",
                    "getAndPutAsync",
                    "putAsync",
                    "putIfAbsentAsync",
                    "removeAsync",
                    "getAndRemoveAsync",
                    "replaceAsync",
                    "getAndReplaceAsync",
                    "clearAsync",
                    "invokeAsync")),
        IgniteCacheAsyncInstrumentation.class.getName() + "$KeyedAdvice");
  }

  public static class IgniteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final IgniteCache<?, ?> that, @Advice.Origin("#m") final String methodName) {
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

      // Enable async propagation, so the newly spawned task will be associated back with this
      // original trace.
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final IgniteFuture<?> future) {

      if (scope == null) {
        return;
      }
      // If we have a scope (i.e. we were the top-level Twilio SDK invocation),
      try {
        final AgentSpan span = scope.span();

        if (throwable != null) {
          // There was an synchronous error,
          // which means we shouldn't wait for a callback to close the span.
          IgniteCacheDecorator.DECORATE.onError(span, throwable);
          IgniteCacheDecorator.DECORATE.beforeFinish(span);
          span.finish();
        } else {
          // We're calling an async operation, we still need to finish the span when it's
          // complete and report the results; set an appropriate callback
          future.listen(new SpanFinishingCallback(span));
        }
      } finally {
        scope.close();
        // span finished in SpanFinishingCallback
        CallDepthThreadLocalMap.reset(IgniteCache.class); // reset call depth count
      }
    }
  }

  public static class KeyedAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.This final IgniteCache<?, ?> that,
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

      // Enable async propagation, so the newly spawned task will be associated back with this
      // original trace.
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final IgniteFuture<?> future) {
      if (scope == null) {
        return;
      }
      // If we have a scope (i.e. we were the top-level Twilio SDK invocation),
      try {
        final AgentSpan span = scope.span();

        if (throwable != null) {
          // There was an synchronous error,
          // which means we shouldn't wait for a callback to close the span.
          IgniteCacheDecorator.DECORATE.onError(span, throwable);
          IgniteCacheDecorator.DECORATE.beforeFinish(span);
          span.finish();
        } else {
          // We're calling an async operation, we still need to finish the span when it's
          // complete and report the results; set an appropriate callback
          future.listen(new SpanFinishingCallback(span));
        }
      } finally {
        scope.close();
        // span finished in SpanFinishingCallback
        CallDepthThreadLocalMap.reset(IgniteCache.class); // reset call depth count
      }
    }
  }
}
