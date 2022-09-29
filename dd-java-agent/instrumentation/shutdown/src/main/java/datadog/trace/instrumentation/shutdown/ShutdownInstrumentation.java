package datadog.trace.instrumentation.shutdown;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.shutdown.ShutdownHelper;
import net.bytebuddy.asm.Advice;

/**
 * This instrumentation intercepts the JVM shutdown process and allows calling an arbitrary code
 * before the shutdown hooks are called.<br>
 */
@AutoService(Instrumenter.class)
public class ShutdownInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  public ShutdownInstrumentation() {
    super("shutdown");
  }

  @Override
  public String instrumentedType() {
    return "java.lang.Shutdown";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
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
