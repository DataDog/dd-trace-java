package datadog.trace.instrumentation.java.lang;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import java.io.InputStream;
import java.util.Set;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class InputStreamInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public InputStreamInstrumentation() {
    super("inputStream");
  }

  /*
  @Override
  public String hierarchyMarkerType() {
    return "java.io.InputStream";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameEndsWith("InputStream");
  }

   */

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(isPublic()).and(takesArgument(0, InputStream.class)),
        InputStreamInstrumentation.class.getName() + "$InputStreamAdvice");
  }

  @Override
  public boolean isApplicable(final Set<TargetSystem> enabledSystems) {
    return enabledSystems.contains(TargetSystem.IAST);
  }

  @Override
  public String instrumentedType() {
    return "java.io.PushbackInputStream";
  }

  public static class InputStreamAdvice {

    @Advice.OnMethodExit
    public static void onExit(
        @Advice.This final InputStream self, @Advice.Argument(0) final InputStream param) {
      InstrumentationBridge.IO.onConstruct(param, self);
    }
  }
}
