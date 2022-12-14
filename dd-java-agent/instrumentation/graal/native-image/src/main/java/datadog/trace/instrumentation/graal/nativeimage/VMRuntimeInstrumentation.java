package datadog.trace.instrumentation.graal.nativeimage;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class VMRuntimeInstrumentation extends AbstractNativeImageInstrumentation
    implements Instrumenter.ForSingleType {

  @Override
  public String instrumentedType() {
    return "org.graalvm.nativeimage.VMRuntime";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("initialize")),
        VMRuntimeInstrumentation.class.getName() + "$InitializeAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {"datadog.trace.agent.tooling.nativeimage.TracerActivation"};
  }

  @Override
  public boolean injectHelperDependencies() {
    return true;
  }

  public static class InitializeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      datadog.trace.agent.tooling.nativeimage.TracerActivation.activate();
    }
  }
}
