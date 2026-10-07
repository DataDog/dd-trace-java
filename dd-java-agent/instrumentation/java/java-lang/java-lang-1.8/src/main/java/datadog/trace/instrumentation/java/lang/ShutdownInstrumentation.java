package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.shutdown.ShutdownHelper;
import java.util.Set;
import net.bytebuddy.asm.Advice;

/**
 * This instrumentation intercepts the JVM shutdown process and allows calling an arbitrary code
 * before the shutdown hooks are called.<br>
 */
@AutoService(InstrumenterModule.class)
public class ShutdownInstrumentation extends InstrumenterModule
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ShutdownInstrumentation() {
    super("shutdown");
  }

  @Override
  public String instrumentedType() {
    return "java.lang.Shutdown";
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    // Preserve tracing's historical hook and include Feature Flagging's bounded final drains even
    // when tracing is disabled. Do not instrument shutdown when both consumers are disabled.
    return enabledSystems.contains(TargetSystem.TRACING)
        || Config.get().isFeatureFlaggingProviderEnabled();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isStatic()).and(named("runHooks")),
        getClass().getName() + "$ShutdownAdvice");
  }

  public static class ShutdownAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void methodEnter() {
      // let's intercept the `runHooks` method before any of the hooks run
      ShutdownHelper.shutdownAgent();
    }

    public static void muzzleCheck() {}
  }
}
