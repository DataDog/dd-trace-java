package datadog.trace.instrumentation.java.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static datadog.trace.instrumentation.java.concurrent.AbstractExecutorInstrumentation.EXEC_NAME;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ComparableRunnable;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ThreadPoolExecutorInstrumentation extends Instrumenter.Tracing
    implements ExcludeFilterProvider {

  public ThreadPoolExecutorInstrumentation() {
    super(EXEC_NAME);
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return extendsClass(named("java.util.concurrent.ThreadPoolExecutor"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(named("execute").and(isMethod()), getClass().getName() + "$Execute");
    transformation.applyAdvice(
        named("beforeExecute")
            .and(isMethod())
            .and(takesArgument(1, named(Runnable.class.getName()))),
        getClass().getName() + "$BeforeExecute");
    transformation.applyAdvice(
        named("afterExecute")
            .and(isMethod())
            .and(takesArgument(0, named(Runnable.class.getName()))),
        getClass().getName() + "$AfterExecute");
    transformation.applyAdvice(
        named("remove").and(isMethod()).and(returns(Runnable.class)),
        getClass().getName() + "$Remove");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        RUNNABLE,
        Arrays.asList(
            "datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper",
            "datadog.trace.bootstrap.instrumentation.java.concurrent.ComparableRunnable"));
  }

  public static final class Execute {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Advice.OnMethodEnter
    public static void wrap(@Advice.Argument(readOnly = false, value = 0) Runnable task) {
      if (task instanceof RunnableFuture || null == task || exclude(RUNNABLE, task)) {
        return;
      }
      if (task instanceof Comparable) {
        task = new ComparableRunnable(task);
      } else {
        task = new Wrapper<>(task);
      }
    }
  }

  public static final class BeforeExecute {

    @Advice.OnMethodEnter
    public static void unwrap(@Advice.Argument(readOnly = false, value = 1) Runnable task) {
      if (task instanceof Wrapper) {
        task = ((Wrapper<?>) task).unwrap();
      }
    }
  }

  public static final class AfterExecute {

    @Advice.OnMethodEnter
    public static void unwrap(@Advice.Argument(readOnly = false, value = 0) Runnable task) {
      if (task instanceof Wrapper) {
        task = ((Wrapper<?>) task).unwrap();
      }
    }
  }

  public static final class Remove {

    @Advice.OnMethodExit
    public static void unwrap(@Advice.Return(readOnly = false) Runnable removed) {
      if (removed instanceof Wrapper) {
        Wrapper<?> wrapper = ((Wrapper<?>) removed);
        wrapper.cancel();
        removed = wrapper.unwrap();
      }
    }
  }
}
