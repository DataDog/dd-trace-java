package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import net.bytebuddy.asm.Advice;
import org.springframework.web.servlet.view.UrlBasedViewResolver;

@AutoService(Instrumenter.class)
public class ModelAndViewInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public ModelAndViewInstrumentation() {
    super("spring-web");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(0, named("java.lang.String"))),
        ModelAndViewInstrumentation.class.getName() + "$RedirectionAdvice");
    transformation.applyAdvice(
        named("setViewName").and(takesArguments(String.class)),
        ModelAndViewInstrumentation.class.getName() + "$RedirectionAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.ModelAndView";
  }

  public static class RedirectionAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void redirection(@Advice.Argument(0) final String viewName) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null
          && viewName != null
          && (viewName.startsWith(UrlBasedViewResolver.REDIRECT_URL_PREFIX)
              || viewName.startsWith(UrlBasedViewResolver.FORWARD_URL_PREFIX))) {
        module.onRedirect(viewName);
      }
    }
  }
}
