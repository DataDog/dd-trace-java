package datadog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

/** Keeps process-lifetime JDK I/O pollers independent of the first requesting thread. */
@AutoService(InstrumenterModule.class)
public final class PollerInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public PollerInstrumentation() {
    super("java-lang", "java-lang-21", "virtual-thread");
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(22) && super.isEnabled();
  }

  @Override
  public String instrumentedType() {
    return "sun.nio.ch.Poller$Pollers";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("start").and(takesArguments(0)).and(returns(void.class)),
        getClass().getName() + "$StartAdvice");
  }

  public static final class StartAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope enter() {
      // Disabling async propagation alone still leaves the raw context on virtual threads.
      return rootContext().attach();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
