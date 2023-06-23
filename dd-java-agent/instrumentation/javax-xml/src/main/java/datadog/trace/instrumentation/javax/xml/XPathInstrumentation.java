package datadog.trace.instrumentation.javax.xml;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.XPathInjectionModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class XPathInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForBootstrap, Instrumenter.ForTypeHierarchy {

  public XPathInstrumentation() {
    super("java-xml");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("javax.xml.xpath.XPath"));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        namedOneOf("compile", "evaluate").and(takesArgument(0, String.class)),
        getClass().getName() + "$XPathAdvice");
  }

  public static class XPathAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) final String expression) {
      final XPathInjectionModule module = InstrumentationBridge.XPATH_INJECTION;
      if (module != null) {
        module.onExpression(expression);
      }
    }
  }
}
