package datadog.trace.instrumentation.rhino;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.rhino.RhinoDecorator.INSTRUMENTATION;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class RhinoInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public RhinoInstrumentation() {
    super(INSTRUMENTATION);
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.mozilla.javascript.Context";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(named("compileImpl")),
        packageName + ".RhinoAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".RhinoAdvice",
        packageName + ".RhinoDecorator",
    };
  }
}
