package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class InputStreamInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  public InputStreamInstrumentation() {
    super("inputStream");
  }

  @Override
  public String hierarchyMarkerType() {
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("java.io.InputStream"));
  }

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

  public static class InputStreamAdvice {

    @Advice.OnMethodExit
    public static void onExit(
        @Advice.This final InputStream self, @Advice.Argument(0) final InputStream param) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      try {
        if (module != null) {
          module.taintParam1IfParam2IsTainted(self, param);
        }
      } catch (final Throwable e) {
        module.onUnexpectedException("InputStreamAdvice onExit threw", e);
      }
    }
  }
}
