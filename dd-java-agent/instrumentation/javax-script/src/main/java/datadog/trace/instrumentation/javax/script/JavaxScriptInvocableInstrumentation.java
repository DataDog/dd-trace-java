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
public class JavaxScriptInvocableInstrumentation extends InstrumenterModule.Tracing
    implements  Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice{
  public JavaxScriptInvocableInstrumentation() {
    super("javax-script");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.script.Invocable";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("invokeFunction"))
            .and(takesArgument(0,named("java.lang.String")))
            .and(isPublic()),
        packageName + ".JavaxScriptInvokeAdvice");
  }
  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".JavaxScriptDecorator",
        packageName + ".JavaxScriptEngineEvalAdvice",
        packageName + ".JavaxScriptInvokeAdvice",
    };
  }
}
