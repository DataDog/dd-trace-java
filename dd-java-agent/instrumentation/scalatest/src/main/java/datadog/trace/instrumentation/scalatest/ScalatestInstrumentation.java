package datadog.trace.instrumentation.scalatest;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import org.scalatest.events.Event;

@AutoService(InstrumenterModule.class)
public class ScalatestInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType {

  public ScalatestInstrumentation() {
    super("ci-visibility", "scalatest");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems);
  }

  @Override
  public String instrumentedType() {
    return "org.scalatest.DispatchReporter";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".retry.SuppressedTestFailedException",
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
  }

  public static class DispatchEventAdvice {
    @Advice.OnMethodEnter
    public static void onDispatchEvent(@Advice.Argument(value = 0) Event event) {
      // Instead of registering our reporter using Scalatest's standard "-C" argument,
      // we hook into internal dispatch reporter.
      // The reason is that Scalatest invokes registered reporters in a separate thread,
      // while we need to process events in the thread where they originate.
      // This is required because test span has to be active in the thread where
      // corresponding test is being executed,
      // so that children spans and coverage records
      // could be properly associated with it.
      DatadogReporter.handle(event);
    }
  }
}
