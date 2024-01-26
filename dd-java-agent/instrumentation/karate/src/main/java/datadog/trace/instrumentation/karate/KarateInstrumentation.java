package datadog.trace.instrumentation.karate;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.intuit.karate.Runner;
import com.intuit.karate.RuntimeHook;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class KarateInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForSingleType {

  public KarateInstrumentation() {
    super("ci-visibility", "karate");
  }

  @Override
  public String instrumentedType() {
    return "com.intuit.karate.Runner$Builder";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".TestEventsHandlerHolder",
      packageName + ".KarateUtils",
      packageName + ".KarateTracingHook"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), KarateInstrumentation.class.getName() + "$KarateAdvice");
  }

  public static class KarateAdvice {
    @Advice.OnMethodExit
    public static void onRunnerBuilderConstructorExit(
        @Advice.This Runner.Builder<?> runnerBuilder) {
      runnerBuilder.hook(new KarateTracingHook());
    }

    // Karate 1.0.0 and above
    public static void muzzleCheck(RuntimeHook runtimeHook) {
      runtimeHook.beforeSuite(null);
    }
  }
}
