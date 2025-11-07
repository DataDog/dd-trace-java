package datadog.trace.instrumentation.hazelcast36;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.hazelcast36.DistributedObjectDecorator.DECORATE;
import static datadog.trace.instrumentation.hazelcast36.HazelcastConstants.SPAN_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.client.spi.impl.ClientNonSmartInvocationServiceImpl;
import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.ICompletableFuture;
import com.hazelcast.spi.discovery.DiscoveryStrategy;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class DistributedObjectInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final String PROXY_PACKAGE = "com.hazelcast.client.proxy";

  public DistributedObjectInstrumentation() {
    super("hazelcast_legacy");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DistributedObjectDecorator",
      packageName + ".DistributedObjectDecorator$1",
      packageName + ".SpanFinishingExecutionCallback",
      packageName + ".HazelcastConstants"
    };
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      PROXY_PACKAGE + ".ClientMapProxy",
      PROXY_PACKAGE + ".ClientReplicatedMapProxy",
      PROXY_PACKAGE + ".ClientQueueProxy",
      PROXY_PACKAGE + ".ClientTopicProxy",
      PROXY_PACKAGE + ".ClientReliableTopicProxy",
      PROXY_PACKAGE + ".ClientSetProxy",
      PROXY_PACKAGE + ".ClientListProxy",
      PROXY_PACKAGE + ".ClientMultiMapProxy",
      PROXY_PACKAGE + ".ClientLockProxy",
      PROXY_PACKAGE + ".ClientRingbufferProxy",
      PROXY_PACKAGE + ".ClientExecutorServiceProxy",
      PROXY_PACKAGE + ".ClientFlakeIdGeneratorProxy",
      PROXY_PACKAGE + ".ClientIdGeneratorProxy",
      PROXY_PACKAGE + ".ClientPNCounterProxy",
      PROXY_PACKAGE + ".ClientCardinalityEstimatorProxy",
      PROXY_PACKAGE + ".ClientSemaphoreProxy"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "acquire",
                    "add",
                    "addAll",
                    "addAndGet",
                    "addIndex",
                    "aggregate",
                    "availablePermits",
                    "awaitTermination",
                    "capacity",
                    "clear",
                    "contains",
                    "containsAll",
                    "containsEntry",
                    "containsKey",
                    "containsValue",
                    "decrementAndGet",
                    "delete",
                    "drainPermits",
                    "drainTo",
                    "element",
                    "entrySet",
                    "estimate",
                    "evict",
                    "evictAll",
                    "execute",
                    "executeOnAllMembers",
                    "executeOnEntries",
                    "executeOnKey",
                    "executeOnKeyOwner",
                    "executeOnKeys",
                    "executeOnMember",
                    "executeOnMembers",
                    "flush",
                    "forceUnlock",
                    "get",
                    "getAll",
                    "getAndAdd",
                    "getAndDecrement",
                    "getAndIncrement",
                    "getAndSubtract",
                    "getEntryView",
                    "getLocalExecutorStats",
                    "getLockCount",
                    "getQueryCache",
                    "getQueryCacheContext",
                    "getRemainingLeaseTime",
                    "headSequence",
                    "increasePermits",
                    "incrementAndGet",
                    "indexOf",
                    "init",
                    "invokeAll",
                    "invokeAny",
                    "isEmpty",
                    "isLocked",
                    "isLockedByCurrentThread",
                    "isShutdown",
                    "isTerminated",
                    "iterator",
                    "keySet",
                    "lastIndexOf",
                    "listIterator",
                    "loadAll",
                    "lock",
                    "newCondition",
                    "newId",
                    "offer",
                    "peek",
                    "poll",
                    "project",
                    "publish",
                    "put",
                    "putAll",
                    "putIfAbsent",
                    "putTransient",
                    "readFromEventJournal",
                    "readOne",
                    "reducePermits",
                    "release",
                    "remainingCapacity",
                    "remove",
                    "removeAll",
                    "replace",
                    "reset",
                    "retainAll",
                    "set",
                    "setTtl",
                    "shutdown",
                    "shutdownNow",
                    "size",
                    "subList",
                    "submitToKey",
                    "submitToKeys",
                    "subscribeToEventJournal",
                    "subtractAndGet",
                    "tailSequence",
                    "take",
                    "toArray",
                    "tryAcquire",
                    "tryLock",
                    "tryPut",
                    "tryRemove",
                    "unlock",
                    "valueCount",
                    "values")),
        getClass().getName() + "$SyncAdvice");

    // Async
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(
                namedOneOf(
                    "addAllAsync",
                    "addAsync",
                    "estimateAsync",
                    "getAsync",
                    "putAsync",
                    "readManyAsync",
                    "removeAsync",
                    "setAsync"))
            .and(returns(NameMatchers.named("com.hazelcast.core.ICompletableFuture"))),
        getClass().getName() + "$CompletableFutureAdvice");
  }

  /** Advice for instrumenting distributed object client proxy classes. */
  public static class SyncAdvice {

    /** Method entry instrumentation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.This final DistributedObject that, @Advice.Origin("#m") final String methodName) {

      // Ensure that we only create a span for the top-level Hazelcast method; except in the
      // case of async operations where we want visibility into how long the task was delayed from
      // starting. Our call depth checker does not span threads, so the async case is handled
      // automatically for us.
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(DistributedObject.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(SPAN_NAME);
      DECORATE.afterStart(span);
      DECORATE.onServiceExecution(span, that, methodName);

      return activateSpan(span);
    }

    /** Method exit instrumentation. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      // If we have a scope (i.e. we were the top-level Hazelcast SDK invocation),
      final AgentSpan span = scope.span();
      try {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
      } finally {
        scope.close();
        span.finish();
        CallDepthThreadLocalMap.reset(DistributedObject.class); // reset call depth count
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy,

        // New in 3.6
        DiscoveryStrategy strategy,

        // Renamed in 3.9
        ClientNonSmartInvocationServiceImpl invocationService) {
      strategy.start();
      proxy.getServiceName();
      invocationService.start();
    }
  }

  /** Advice for instrumenting distributed object client proxy classes. */
  public static class CompletableFutureAdvice {

    /** Method entry instrumentation. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope methodEnter(
        @Advice.This final DistributedObject that, @Advice.Origin("#m") final String methodName) {

      // Ensure that we only create a span for the top-level Hazelcast method; except in the
      // case of async operations where we want visibility into how long the task was delayed from
      // starting. Our call depth checker does not span threads, so the async case is handled
      // automatically for us.
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(DistributedObject.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(SPAN_NAME);
      DECORATE.afterStart(span);
      DECORATE.onServiceExecution(span, that, methodName);

      return activateSpan(span);
    }

    /** Method exit instrumentation. */
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void methodExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Return final ICompletableFuture<?> future) {
      if (scope == null) {
        return;
      }

      // If we have a scope (i.e. we were the top-level Hazelcast SDK invocation),
      final AgentSpan span = scope.span();
      try {
        if (throwable != null) {
          // There was an synchronous error,
          // which means we shouldn't wait for a callback to close the span.
          DECORATE.onError(span, throwable);
          DECORATE.beforeFinish(span);
          span.finish();
        } else {
          future.andThen(new SpanFinishingExecutionCallback(span));
        }
      } finally {
        scope.close();
        CallDepthThreadLocalMap.reset(DistributedObject.class); // reset call depth count
      }
    }

    public static void muzzleCheck(
        // Moved in 4.0
        ClientMapProxy proxy,

        // New in 3.6
        DiscoveryStrategy strategy,

        // Renamed in 3.9
        ClientNonSmartInvocationServiceImpl invocationService,

        // Required for async instrumentation
        ICompletableFuture future) {
      strategy.start();
      proxy.getServiceName();
      invocationService.start();
    }
  }
}
