import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class IBMResourceLevelInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public IBMResourceLevelInstrumentation() {
    super(IBMResourceLevelInstrumentation.class.getName());
  }

  @Override
  public String instrumentedType() {
    return "com.ibm.as400.resource.ResourceLevel";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(named("toString"), ToStringAdvice.class.getName());
  }

  public static class ToStringAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void toStringReplace(@Advice.Return(readOnly = false) String ret) {
      ret = "instrumented";
    }
  }
}
