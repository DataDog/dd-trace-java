import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import stackstate.trace.agent.tooling.Instrumenter;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class IBMResourceLevelInstrumentation extends Instrumenter.Default {
  public IBMResourceLevelInstrumentation() {
    super(IBMResourceLevelInstrumentation.class.getName());
  }

  @Override
  public ElementMatcher typeMatcher() {
    return named("com.ibm.as400.resource.ResourceLevel");
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(named("toString"), ToStringAdvice.class.getName());
    return transformers;
  }

  public static class ToStringAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void toStringReplace(@Advice.Return(readOnly = false) String ret) {
      ret = "instrumented";
    }
  }
}
