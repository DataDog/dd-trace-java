package datadog.trace.instrumentation.java.concurrent.runnable;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.notExcludedByName;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.capture;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public final class RunnableFutureInstrumentation
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice,
        ExcludeFilterProvider {

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return notExcludedByName(RUNNABLE_FUTURE)
        .and(
            extendsClass(
                named("java.util.concurrent.FutureTask")
                    .or(nameEndsWith(".netty.util.concurrent.PromiseTask"))
                    .or(
                        nameEndsWith(
                            "com.google.common.util.concurrent.TrustedListenableFutureTask"))));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // instrument any FutureTask or TrustedListenableFutureTask constructor,
    // but only instrument the PromiseTask constructor with a Callable argument
    transformer.applyAdvice(
        isConstructor()
            .and(
                isDeclaredBy(
                        named("java.util.concurrent.FutureTask")
                            .or(
                                nameEndsWith(
                                    "com.google.common.util.concurrent.TrustedListenableFutureTask")))
                    .or(
                        isDeclaredBy(nameEndsWith(".netty.util.concurrent.PromiseTask"))
                            .and(takesArgument(1, named(Callable.class.getName()))))),
        getClass().getName() + "$Construct");
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$Construct");
    transformer.applyAdvice(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformer.applyAdvice(
        isMethod().and(namedOneOf("cancel", "set", "setException")),
        getClass().getName() + "$Cancel");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    // This is a footprint optimisation, not required for correctness.
    // exclude as many known task implementations as possible because
    // the fields add up. This could be removed when we stop injecting
    // Runnables at all.
    return singletonMap(
        RUNNABLE,
        Arrays.asList(
            "com.couchbase.client.deps.io.netty.util.concurrent.PromiseTask",
            "com.couchbase.client.deps.io.netty.util.concurrent.RunnableScheduledFutureTask",
            "com.couchbase.client.deps.io.netty.util.concurrent.ScheduledFutureTask",
            "com.couchbase.client.deps.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$NonNotifyRunnable",
            "com.couchbase.client.deps.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$RunnableScheduledFutureTask",
            "com.google.common.util.concurrent.Futures$CombinerFuture",
            "com.google.common.util.concurrent.ListenableFutureTask",
            "com.google.common.util.concurrent.TrustedListenableFutureTask",
            "com.sun.jersey.client.impl.async.FutureClientResponseListener",
            "io.grpc.netty.shaded.io.netty.util.concurrent.PromiseTask",
            "io.grpc.netty.shaded.io.netty.util.concurrent.RunnableScheduledFutureTask",
            "io.grpc.netty.shaded.io.netty.util.concurrent.ScheduledFutureTask",
            "io.grpc.netty.shaded.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$NonNotifyRunnable",
            "io.grpc.netty.shaded.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$RunnableScheduledFutureTask",
            "io.netty.util.concurrent.PromiseTask",
            "io.netty.util.concurrent.RunnableScheduledFutureTask",
            "io.netty.util.concurrent.ScheduledFutureTask",
            "io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$NonNotifyRunnable",
            "io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$RunnableScheduledFutureTask",
            "java.util.concurrent.ExecutorCompletionService$QueueingFuture",
            "java.util.concurrent.FutureTask",
            "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask",
            "jersey.repackaged.com.google.common.util.concurrent.ListenableFutureTask",
            "org.apache.cassandra.concurrent.DebuggableThreadPoolExecutor$LocalSessionWrapper",
            "org.apache.http.impl.client.HttpRequestFutureTask",
            "org.elasticsearch.common.util.concurrent.PrioritizedEsThreadPoolExecutor$PrioritizedFutureTask",
            "org.glassfish.enterprise.concurrent.internal.ManagedFutureTask",
            "org.glassfish.enterprise.concurrent.internal.ManagedScheduledThreadPoolExecutor$ManagedScheduledFutureTask",
            "org.glassfish.enterprise.concurrent.internal.ManagedScheduledThreadPoolExecutor$ManagedTriggerSingleFutureTask",
            "org.springframework.boot.SpringApplicationShutdownHook",
            "org.springframework.util.concurrent.ListenableFutureTask",
            "org.springframework.util.concurrent.SettableListenableFuture$SettableTask",
            "play.shaded.ahc.io.netty.util.concurrent.PromiseTask",
            "play.shaded.ahc.io.netty.util.concurrent.RunnableScheduledFutureTask",
            "play.shaded.ahc.io.netty.util.concurrent.ScheduledFutureTask"));
  }

  public static final class Construct {

    @Advice.OnMethodExit
    public static <T> void captureScope(@Advice.This RunnableFuture<T> task) {
      capture(InstrumentationContext.get(RunnableFuture.class, State.class), task);
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> AgentScope activate(@Advice.This RunnableFuture<T> task) {
      return startTaskScope(InstrumentationContext.get(RunnableFuture.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void close(@Advice.Enter AgentScope scope) {
      endTaskScope(scope);
    }
  }

  public static final class Cancel {
    @Advice.OnMethodEnter
    public static <T> void cancel(@Advice.This RunnableFuture<T> task) {
      cancelTask(InstrumentationContext.get(RunnableFuture.class, State.class), task);
    }
  }
}
