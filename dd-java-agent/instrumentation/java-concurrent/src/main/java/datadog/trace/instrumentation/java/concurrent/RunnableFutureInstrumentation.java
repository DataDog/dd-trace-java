package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.cancelTask;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.endTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils.startTaskScope;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE_FUTURE;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class RunnableFutureInstrumentation extends Instrumenter.Tracing
    implements ExcludeFilterProvider {
  public RunnableFutureInstrumentation() {
    super("java_concurrent", "runnable-future");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return extendsClass(
            named("java.util.concurrent.FutureTask")
                .or(nameEndsWith(".netty.util.concurrent.PromiseTask"))
                .or(nameEndsWith("com.google.common.util.concurrent.TrustedListenableFutureTask")))
        .and(
            new ElementMatcher.Junction.AbstractBase<TypeDescription>() {
              @Override
              public boolean matches(TypeDescription target) {
                return !ExcludeFilter.exclude(RUNNABLE_FUTURE, target.getName());
              }
            });
  }

  @Override
  public Map<String, String> contextStoreForAll() {
    return singletonMap("java.util.concurrent.RunnableFuture", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    Map<ElementMatcher<MethodDescription>, String> transformers = new HashMap<>(8);
    // TODO should target some particular implementations to prevent this from happening all
    //  the way up the constructor chain (even though the advice applied is cheap)
    transformers.put(isConstructor(), getClass().getName() + "$Construct");
    transformers.put(isMethod().and(named("run")), getClass().getName() + "$Run");
    transformers.put(isMethod().and(named("cancel")), getClass().getName() + "$Cancel");
    return unmodifiableMap(transformers);
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
            "java.util.concurrent.FutureTask",
            "java.util.concurrent.ScheduledThreadPoolExecutor$ScheduledFutureTask",
            "java.util.concurrent.ExecutorCompletionService$QueueingFuture",
            "io.netty.util.concurrent.PromiseTask",
            "io.netty.util.concurrent.ScheduledFutureTask",
            "io.netty.util.concurrent.RunnableScheduledFutureTask",
            "io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$RunnableScheduledFutureTask",
            "io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$NonNotifyRunnable",
            "io.grpc.netty.shaded.io.netty.util.concurrent.PromiseTask",
            "io.grpc.netty.shaded.io.netty.util.concurrent.ScheduledFutureTask",
            "io.grpc.netty.shaded.io.netty.util.concurrent.RunnableScheduledFutureTask",
            "io.grpc.netty.shaded.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$RunnableScheduledFutureTask",
            "io.grpc.netty.shaded.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$NonNotifyRunnable",
            "com.couchbase.client.deps.io.netty.util.concurrent.PromiseTask",
            "com.couchbase.client.deps.io.netty.util.concurrent.ScheduledFutureTask",
            "com.couchbase.client.deps.io.netty.util.concurrent.RunnableScheduledFutureTask",
            "com.couchbase.client.deps.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$RunnableScheduledFutureTask",
            "com.couchbase.client.deps.io.netty.util.concurrent.UnorderedThreadPoolEventExecutor$NonNotifyRunnable",
            "play.shaded.ahc.io.netty.util.concurrent.PromiseTask",
            "play.shaded.ahc.io.netty.util.concurrent.ScheduledFutureTask",
            "play.shaded.ahc.io.netty.util.concurrent.RunnableScheduledFutureTask",
            "com.google.common.util.concurrent.TrustedListenableFutureTask",
            "com.google.common.util.concurrent.ListenableFutureTask",
            "com.google.common.util.concurrent.Futures$CombinerFuture",
            "org.springframework.util.concurrent.ListenableFutureTask",
            "org.springframework.util.concurrent.SettableListenableFuture$SettableTask",
            "jersey.repackaged.com.google.common.util.concurrent.ListenableFutureTask",
            "com.sun.jersey.client.impl.async.FutureClientResponseListener",
            "org.apache.http.impl.client.HttpRequestFutureTask",
            "org.glassfish.enterprise.concurrent.internal.ManagedFutureTask",
            "org.glassfish.enterprise.concurrent.internal.ManagedScheduledThreadPoolExecutor$ManagedScheduledFutureTask",
            "org.glassfish.enterprise.concurrent.internal.ManagedScheduledThreadPoolExecutor$ManagedTriggerSingleFutureTask",
            "org.elasticsearch.common.util.concurrent.PrioritizedEsThreadPoolExecutor$PrioritizedFutureTask",
            "org.apache.cassandra.concurrent.DebuggableThreadPoolExecutor$LocalSessionWrapper"));
  }

  public static final class Construct {

    @Advice.OnMethodExit
    public static <T> void captureScope(@Advice.This RunnableFuture<T> task) {
      TraceScope activeScope = activeScope();
      if (null != activeScope) {
        InstrumentationContext.get(RunnableFuture.class, State.class)
            .putIfAbsent(task, State.FACTORY)
            .captureAndSetContinuation(activeScope);
      }
    }
  }

  public static final class Run {
    @Advice.OnMethodEnter
    public static <T> TraceScope activate(@Advice.This RunnableFuture<T> task) {
      return startTaskScope(InstrumentationContext.get(RunnableFuture.class, State.class), task);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void close(@Advice.Enter TraceScope scope) {
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
