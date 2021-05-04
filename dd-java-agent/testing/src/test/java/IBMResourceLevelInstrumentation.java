import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class IBMResourceLevelInstrumentation extends Instrumenter.Tracing {
  public IBMResourceLevelInstrumentation() {
    super(IBMResourceLevelInstrumentation.class.getName());
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.ibm.as400.resource.ResourceLevel");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(named("toString"), ToStringAdvice.class.getName());
  }

  public static class ToStringAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void toStringReplace(@Advice.Return(readOnly = false) String ret) {
      ret = "instrumented";
    }
  }
}
