package datadog.trace.instrumentation.scalatest;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.scalatest.Reporter;
import org.scalatest.events.Event;

@AutoService(InstrumenterModule.class)
public class ScalatestInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public ScalatestInstrumentation() {
    super("ci-visibility", "scalatest");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.scalatest.DispatchReporter", "org.scalatest.tools.TestSortingReporter",
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".execution.SuppressedTestFailedException",
      packageName + ".ScalatestUtils",
      packageName + ".RunContext",
      packageName + ".DatadogReporter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("apply")
            .and(takesArguments(1))
            .and(takesArgument(0, named("org.scalatest.events.Event"))),
        ScalatestInstrumentation.class.getName() + "$DispatchEventAdvice");
    transformer.applyAdvice(
        named("fireReadyEvents"),
        ScalatestInstrumentation.class.getName() + "$SuppressAsyncEventsAdvice");
  }

  public static class DispatchEventAdvice {
    @Advice.OnMethodEnter
    public static void onDispatchEvent(@Advice.Argument(value = 0) Event event) {
      if (CallDepthThreadLocalMap.incrementCallDepth(Reporter.class) != 0) {
        // nested call
        return;
      }

      // Instead of registering our reporter using Scalatest's standard "-C" argument,
      // we hook into internal reporter.
      // The reason is that Scalatest invokes registered reporters in a separate thread,
      // while we need to process events in the thread where they originate.
      // This is required because test span has to be active in the thread where
      // corresponding test is being executed,
      // so that children spans and coverage records
      // could be properly associated with it.
      DatadogReporter.handle(event);
    }

    @Advice.OnMethodExit
    public static void afterDispatchEvent() {
      CallDepthThreadLocalMap.decrementCallDepth(Reporter.class);
    }
  }

  /**
   * {@link org.scalatest.tools.TestSortingReporter#fireReadyEvents} is triggered asynchronously. It
   * fires some events that are then delegated to other reporters. We need to suppress them (by
   * increasing the call depth so that {@link DispatchEventAdvice} is aborted) as the same events
   * are reported earlier synchronously from {@link org.scalatest.tools.TestSortingReporter#apply}
   */
  public static class SuppressAsyncEventsAdvice {
    @Advice.OnMethodEnter
    public static void onAsyncEventsTrigger() {
      CallDepthThreadLocalMap.incrementCallDepth(Reporter.class);
    }

    @Advice.OnMethodExit
    public static void afterAsyncEventsTrigger() {
      CallDepthThreadLocalMap.decrementCallDepth(Reporter.class);
    }
  }
}
