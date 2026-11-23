package datadog.trace.instrumentation.java.lang.jdk21;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.rootContext;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

/** Keeps JDK I/O pollers independent of the request that first needs them. */
@AutoService(InstrumenterModule.class)
public final class PollerInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public PollerInstrumentation() {
    super("java-lang", "java-lang-21", "virtual-thread");
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(22) && super.isEnabled();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "sun.nio.ch.Poller$Pollers",
      "sun.nio.ch.Poller$VThreadsPollerGroup",
      "sun.nio.ch.Poller$PollerPerCarrierPollerGroup"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("start")
            .and(takesArguments(0))
            .and(returns(void.class))
            .or(
                named("startReadPoller")
                    .and(takesArguments(0))
                    .and(returns(named("sun.nio.ch.Poller")))),
        getClass().getName() + "$StartAdvice");
  }

  public static final class StartAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Context enter() {
      // Swap clears the raw context even when the caller has reached the scope-depth limit.
      return rootContext().swap();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter Context previous) {
      if (previous != null) {
        previous.swap();
      }
    }
  }
}
