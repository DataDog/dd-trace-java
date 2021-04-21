package datadog.trace.instrumentation.v3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.HAZELCAST_SDK;
import static datadog.trace.instrumentation.v3.DistributedObjectDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.google.auto.service.AutoService;
import com.hazelcast.client.proxy.ClientMapProxy;
import com.hazelcast.client.spi.impl.ClientNonSmartInvocationServiceImpl;
import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.ICompletableFuture;
import com.hazelcast.spi.discovery.DiscoveryStrategy;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.hazelcast.HazelcastConstants;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class DistributedObjectInstrumentation extends Instrumenter.Tracing {

  private static final String PROXY_PACKAGE = "com.hazelcast.client.proxy";

  public DistributedObjectInstrumentation() {
    super(HazelcastConstants.INSTRUMENTATION_NAME);
  }

  @Override
  protected boolean defaultEnabled() {
    return HazelcastConstants.DEFAULT_ENABLED;
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DistributedObjectDecorator",
      packageName + ".DistributedObjectDecorator$1",
      packageName + ".SpanFinishingExecutionCallback",
      "datadog.trace.instrumentation.hazelcast.HazelcastConstants"
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return namedOneOf(
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
        PROXY_PACKAGE + ".ClientSemaphoreProxy");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "com.hazelcast.core.DistributedObject", "com.hazelcast.core.HazelcastInstance");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();

    // Map
    transformers.put(
        isMethod()
            .and(
                isDeclaredBy(
                    namedOneOf(
                        PROXY_PACKAGE + ".ClientMapProxy",
                        PROXY_PACKAGE + ".ClientReplicatedMapProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "addIndex",
                    "aggregate",
                    "clear",
                    "containsKey",
                    "containsValue",
                    "delete",
                    "entrySet",
                    "evict",
                    "evictAll",
                    "executeOnEntries",
                    "executeOnKey",
                    "executeOnKeys",
                    "flush",
                    "forceUnlock",
                    "get",
                    "getAll",
                    "getEntryView",
                    "getQueryCache",
                    "getQueryCacheContext",
                    "isEmpty",
                    "isLocked",
                    "iterator",
                    "keySet",
                    "loadAll",
                    "lock",
                    "project",
                    "put",
                    "putAll",
                    "putIfAbsent",
                    "putTransient",
                    "readFromEventJournal",
                    "remove",
                    "removeAll",
                    "replace",
                    "set",
                    "setTtl",
                    "size",
                    "submitToKey",
                    "submitToKeys",
                    "subscribeToEventJournal",
                    "tryLock",
                    "tryPut",
                    "tryRemove",
                    "unlock",
                    "values")),
        getClass().getName() + "$SyncAdvice");
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientMapProxy")))
            .and(isPublic())
            .and(namedOneOf("getAsync", "putAsync", "removeAsync", "setAsync"))
            .and(returns(NameMatchers.named("com.hazelcast.core.ICompletableFuture"))),
        getClass().getName() + "$CompletableFutureAdvice");

    // MultiMap
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientMultiMapProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "aggregate",
                    "clear",
                    "containsEntry",
                    "containsKey",
                    "containsValue",
                    "delete",
                    "entrySet",
                    "forceUnlock",
                    "get",
                    "isLocked",
                    "keySet",
                    "lock",
                    "put",
                    "remove",
                    "size",
                    "tryLock",
                    "unlock",
                    "valueCount",
                    "values")),
        getClass().getName() + "$SyncAdvice");

    // Topic
    // TODO Handle asynchronous receive messages
    transformers.put(
        isMethod()
            .and(
                isDeclaredBy(
                    namedOneOf(
                        PROXY_PACKAGE + ".ClientTopicProxy",
                        PROXY_PACKAGE + ".ClientReliableTopicProxy")))
            .and(isPublic())
            .and(namedOneOf("publish")),
        getClass().getName() + "$SyncAdvice");

    // Queue
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientQueueProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "add",
                    "addAll",
                    "clear",
                    "contains",
                    "containsAll",
                    "drainTo",
                    "element",
                    "isEmpty",
                    "iterator",
                    "offer",
                    "peek",
                    "poll",
                    "put",
                    "remainingCapacity",
                    "remove",
                    "removeAll",
                    "retainAll",
                    "size",
                    "take",
                    "toArray")),
        getClass().getName() + "$SyncAdvice");

    // Set and List
    transformers.put(
        isMethod()
            .and(
                isDeclaredBy(
                    namedOneOf(
                        PROXY_PACKAGE + ".ClientSetProxy", PROXY_PACKAGE + ".ClientListProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "add",
                    "addAll",
                    "clear",
                    "contains",
                    "containsAll",
                    "get",
                    "indexOf",
                    "isEmpty",
                    "iterator",
                    "lastIndexOf",
                    "listIterator",
                    "remove",
                    "removeAll",
                    "retainAll",
                    "set",
                    "size",
                    "subList",
                    "toArray")),
        getClass().getName() + "$SyncAdvice");

    // Lock (deprecated)
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientLockProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "forceUnlock",
                    "getLockCount",
                    "getRemainingLeaseTime",
                    "isLocked",
                    "isLockedByCurrentThread",
                    "lock",
                    "newCondition",
                    "tryLock",
                    "unlock")),
        getClass().getName() + "$SyncAdvice");

    // Ring Buffer
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientRingbufferProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "add",
                    "capacity",
                    "headSequence",
                    "readOne",
                    "remainingCapacity",
                    "size",
                    "tailSequence")),
        getClass().getName() + "$SyncAdvice");
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientRingbufferProxy")))
            .and(isPublic())
            .and(namedOneOf("addAllAsync", "addAsync", "readManyAsync"))
            .and(returns(NameMatchers.named("com.hazelcast.core.ICompletableFuture"))),
        getClass().getName() + "$CompletableFutureAdvice");

    // Executor Service
    // TODO support asynchronous execution via submit methods
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientExecutorServiceProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "awaitTermination",
                    "execute",
                    "executeOnAllMembers",
                    "executeOnKeyOwner",
                    "executeOnMember",
                    "executeOnMembers",
                    "getLocalExecutorStats",
                    "invokeAll",
                    "invokeAny",
                    "isShutdown",
                    "isTerminated",
                    "shutdown",
                    "shutdownNow")),
        getClass().getName() + "$SyncAdvice");

    // ID Generators
    transformers.put(
        isMethod()
            .and(
                isDeclaredBy(
                    namedOneOf(
                        PROXY_PACKAGE + ".ClientRingbufferProxy",
                        PROXY_PACKAGE + ".ClientIdGeneratorProxy")))
            .and(isPublic())
            .and(namedOneOf("newId")),
        getClass().getName() + "$SyncAdvice");

    // PN Counter
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientPNCounterProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "addAndGet",
                    "decrementAndGet",
                    "get",
                    "getAndAdd",
                    "getAndDecrement",
                    "getAndIncrement",
                    "getAndSubtract",
                    "incrementAndGet",
                    "reset",
                    "subtractAndGet")),
        getClass().getName() + "$SyncAdvice");

    // Cardinality Estimator
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientCardinalityEstimatorProxy")))
            .and(isPublic())
            .and(namedOneOf("add", "estimate")),
        getClass().getName() + "$SyncAdvice");
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientCardinalityEstimatorProxy")))
            .and(isPublic())
            .and(namedOneOf("addAsync", "estimateAsync"))
            .and(returns(NameMatchers.named("com.hazelcast.core.ICompletableFuture"))),
        getClass().getName() + "$CompletableFutureAdvice");

    // Semaphore (Deprecated)
    transformers.put(
        isMethod()
            .and(isDeclaredBy(named(PROXY_PACKAGE + ".ClientSemaphoreProxy")))
            .and(isPublic())
            .and(
                namedOneOf(
                    "acquire",
                    "availablePermits",
                    "drainPermits",
                    "increasePermits",
                    "init",
                    "reducePermits",
                    "release",
                    "tryAcquire")),
        getClass().getName() + "$SyncAdvice");

    // TODO support IScheduledExecutorService

    return transformers;
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

      final AgentSpan span = startSpan(HAZELCAST_SDK);
      DECORATE.onHazelcastInstance(
          span,
          InstrumentationContext.get(DistributedObject.class, HazelcastInstance.class).get(that));
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

      final AgentSpan span = startSpan(HAZELCAST_SDK);
      DECORATE.onHazelcastInstance(
          span,
          InstrumentationContext.get(DistributedObject.class, HazelcastInstance.class).get(that));
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
