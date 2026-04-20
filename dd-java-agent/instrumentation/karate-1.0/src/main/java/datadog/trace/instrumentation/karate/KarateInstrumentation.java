package datadog.trace.instrumentation.karate;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import com.intuit.karate.Runner;
import com.intuit.karate.RuntimeHook;
import com.intuit.karate.core.FeatureRuntime;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class KarateInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
      packageName + ".KarateUtils",
      packageName + ".TestEventsHandlerHolder",
      packageName + ".KarateTracingHook"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap("com.intuit.karate.core.FeatureRuntime", "java.lang.Boolean");
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
      ContextStore<FeatureRuntime, Boolean> featureRuntimeContextStore =
          InstrumentationContext.get(FeatureRuntime.class, Boolean.class);
      runnerBuilder.hook(new KarateTracingHook(featureRuntimeContextStore));
    }

    // Karate 1.0.0 and above
    public static void muzzleCheck(RuntimeHook runtimeHook) {
      runtimeHook.beforeSuite(null);
    }
  }
}
