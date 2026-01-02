package datadog.trace.instrumentation.aerospike4;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.exclude;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class NioEventLoopInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.aerospike.client.async.NioEventLoop";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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
      task = new FutureTask<Void>(task, null);
    }
  }
}
