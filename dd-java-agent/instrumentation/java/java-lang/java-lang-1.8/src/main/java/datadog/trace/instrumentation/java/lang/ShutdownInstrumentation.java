package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
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
    // Agent-owned subsystems such as Feature Flagging can run while tracing is disabled. Their
    // bounded final drains still depend on ShutdownHelper running before application hooks.
    return true;
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
