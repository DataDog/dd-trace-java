package datadog.trace.instrumentation.netty40.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

/** Prevents Netty's lazily created event-loop maintenance tasks from inheriting user context. */
@AutoService(InstrumenterModule.class)
public final class SingleThreadEventExecutorStartThreadInstrumentation
    extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public SingleThreadEventExecutorStartThreadInstrumentation() {
    super("netty-concurrent", "netty-event-executor");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.netty.util.concurrent.SingleThreadEventExecutor",
      "io.grpc.netty.shaded.io.netty.util.concurrent.SingleThreadEventExecutor",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("startThread").and(takesNoArguments()).and(returns(void.class)).and(isPrivate()),
        getClass().getName() + "$DisableAsyncPropagation");
  }

  public static final class DisableAsyncPropagation {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean before() {
      if (isAsyncPropagationEnabled()) {
        setAsyncPropagationEnabled(false);
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter boolean wasDisabled) {
      if (wasDisabled) {
        setAsyncPropagationEnabled(true);
      }
    }
  }
}
