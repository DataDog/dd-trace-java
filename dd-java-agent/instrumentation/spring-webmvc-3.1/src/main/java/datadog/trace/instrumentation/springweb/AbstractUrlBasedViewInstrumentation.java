package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.web.servlet.view.RedirectView;

@AutoService(Instrumenter.class)
public class AbstractUrlBasedViewInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForTypeHierarchy {

  public AbstractUrlBasedViewInstrumentation() {
    super("spring-web");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.web.servlet.view.AbstractUrlBasedView";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArguments(String.class)),
        AbstractUrlBasedViewInstrumentation.class.getName() + "$RedirectionAdvice");
    transformation.applyAdvice(
        named("setUrl").and(takesArguments(String.class)),
        AbstractUrlBasedViewInstrumentation.class.getName() + "$RedirectionAdvice");
  }

  public static class RedirectionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void redirection(
        @Advice.This Object urlBasedView, @Advice.Argument(0) final String url) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null && url != null && urlBasedView instanceof RedirectView) {
        module.onRedirect(url);
      }
    }
  }
}
