package datadog.trace.instrumentation.scalatest;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

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
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ScalatestForkInstrumentation() {
    super("ci-visibility", "scalatest");
  }

  @Override
  public boolean isEnabled() {
    return Config.get().isCiVisibilityScalatestForkMonitorEnabled();
  }

  @Override
  public String instrumentedType() {
    return "org.scalatest.tools.Framework";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("runner").and(takesArgument(1, String[].class)),
        ScalatestForkInstrumentation.class.getName() + "$RunnerAdvice");
  }

  /**
   * This instrumentation is needed to prevent double-reporting of Scalatest events when using SBT
   * and Test/fork. Current version of the code cannot detect such cases automatically, so it has to
   * be activated explicitly by setting {@link
   * CiVisibilityConfig#CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED}.
   *
   * <p>When using SBT with test forking, Scalatest reporter is activated both in the parent and in
   * the child process. We only want to instrument the child process (since it is actually executing
   * the tests) and not the parent one. The way to distinguish between the two is by examining the
   * "remoteArgs" passed to the runner method: the args are non-empty in the child process, and
   * empty in the parent one.
   *
   * <p>The instrumentation works by increasing the Reporter.class counter in the call depth thread
   * local map. The counter is decreased when the runner method exits.
   *
   * <p>Since the tracing instrumentation is checking the counter value to avoid nested calls,
   * increasing it here results in effectively disabling the tracing.
   */
  public static class RunnerAdvice {
    @Advice.OnMethodEnter
    public static void beforeRunnerStart(@Advice.Argument(value = 1) String[] remoteArgs) {
      if (remoteArgs.length == 0) {
        CallDepthThreadLocalMap.incrementCallDepth(Reporter.class);
      }
    }

    @Advice.OnMethodExit
    public static void afterRunnerStart(@Advice.Argument(value = 1) String[] remoteArgs) {
      if (remoteArgs.length == 0) {
        CallDepthThreadLocalMap.decrementCallDepth(Reporter.class);
      }
    }
  }
}
