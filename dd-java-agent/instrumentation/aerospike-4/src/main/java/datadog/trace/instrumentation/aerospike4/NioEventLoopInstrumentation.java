package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class NioEventLoopInstrumentation extends Instrumenter.Default {
  public NioEventLoopInstrumentation() {
    super("aerospike", "java_concurrent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.aerospike.client.async.NioEventLoop");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("execute"))
            .and(takesArguments(1))
            .and(takesArgument(0, Runnable.class)),
        getClass().getName() + "$WrapAsFutureTaskAdvice");
  }

  public static final class WrapAsFutureTaskAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enterExecute(@Advice.Argument(value = 0, readOnly = false) Runnable task) {
      if (task == null || task instanceof RunnableFuture || exclude(RUNNABLE, task)) {
        return;
      }
      // don't wrap Runnables belonging to NioEventLoop(s) as they want to propagate CloseException
      // outside of the event loop on close() and wrapping them in FutureTask interferes with that
      if (task.getClass().getName().startsWith("com.aerospike.client.async.NioEventLoop")) {
        return;
      }
      task = new FutureTask<Void>(task, null);
    }
  }
}
