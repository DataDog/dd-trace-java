package datadog.trace.instrumentation.scalatest;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import org.scalatest.Reporter;

@AutoService(InstrumenterModule.class)
public class ScalatestForkInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public ScalatestForkInstrumentation() {
    super("ci-visibility", "scalatest");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && Config.get().isCiVisibilityScalatestForkMonitorEnabled();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.scalatest.tools.Framework$ScalaTestRunner$Skeleton$1",
      "org.scalatest.tools.Framework$Skeleton$1",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("run").and(takesArguments(0)),
        ScalatestForkInstrumentation.class.getName() + "$SkeletonRunAdvice");
  }

  /**
   * Prevents double-reporting of Scalatest events when using SBT with Test/fork. Activated with
   * {@link CiVisibilityConfig#CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED}, since the parent JVM is
   * the only one reporting when the agent is not attached to the forked JVM.
   *
   * <p>The parent JVM receives the forked JVM's events in the {@code Skeleton} server thread, which
   * only exists when SBT forks the tests. Increasing the Reporter.class call depth in this thread
   * makes the tracing instrumentation ignore these events.
   */
  public static class SkeletonRunAdvice {
    @Advice.OnMethodEnter
    public static void beforeRun() {
      CallDepthThreadLocalMap.incrementCallDepth(Reporter.class);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void afterRun() {
      CallDepthThreadLocalMap.decrementCallDepth(Reporter.class);
    }
  }
}
