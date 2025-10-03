package datadog.trace.instrumentation.java.lang;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class InputStreamInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  private static final String[] FORCE_LOADING = {"java.io.PushbackInputStream"};

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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArgument(0, InputStream.class)),
        InputStreamInstrumentation.class.getName() + "$InputStreamAdvice");
  }

  @Override
  public String[] getClassNamesToBePreloaded() {
    return FORCE_LOADING;
  }

  public static class InputStreamAdvice {

    @Advice.OnMethodExit
    @Propagation
    public static void onExit(
        @Advice.This final InputStream self, @Advice.Argument(0) final InputStream param) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      try {
        if (module != null) {
          module.taintObjectIfTainted(self, param);
        }
      } catch (final Throwable e) {
        module.onUnexpectedException("InputStreamAdvice onExit threw", e);
      }
    }
  }
}
