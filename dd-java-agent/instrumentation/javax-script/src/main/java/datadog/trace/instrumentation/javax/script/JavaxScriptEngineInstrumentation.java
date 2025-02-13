package datadog.trace.instrumentation.javax.script;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

@AutoService(InstrumenterModule.class)
public class JavaxScriptEngineInstrumentation extends InstrumenterModule.Tracing
    implements  Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice{
  public JavaxScriptEngineInstrumentation() {
    super("javax-script");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.script.ScriptEngine";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
        .and(named("eval"))
        .and(takesArguments(2))
        .and(isPublic()),
        packageName + ".JavaxScriptEngineEvalAdvice");
  }
  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".JavaxScriptDecorator",
        packageName + ".JavaxScriptEngineEvalAdvice",
    };
  }
}
