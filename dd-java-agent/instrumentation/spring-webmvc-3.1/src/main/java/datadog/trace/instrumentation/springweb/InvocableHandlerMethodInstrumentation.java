package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import net.bytebuddy.asm.Advice;
import org.springframework.web.method.support.InvocableHandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.AbstractUrlBasedView;

@AutoService(Instrumenter.class)
public final class InvocableHandlerMethodInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public InvocableHandlerMethodInstrumentation() {
    super("spring-web");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("invokeForRequest")),
        InvocableHandlerMethodInstrumentation.class.getName() + "$ControllerAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.method.support.InvocableHandlerMethod";
  }

  public static class ControllerAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.UNVALIDATED_REDIRECT)
    public static void checkReturnedObject(
        @Advice.Return Object returned, @Advice.This InvocableHandlerMethod self) {
      final UnvalidatedRedirectModule module = InstrumentationBridge.UNVALIDATED_REDIRECT;
      if (module != null && returned != null) {
        String clazz = self.getMethod().getDeclaringClass().getName();
        String method = self.getMethod().getName();
        if (returned instanceof AbstractUrlBasedView) {
          module.onRedirect(((AbstractUrlBasedView) returned).getUrl(), clazz, method);
        } else if (returned instanceof ModelAndView) {
          module.onRedirect(((ModelAndView) returned).getViewName(), clazz, method);
        } else if (returned instanceof String) {
          module.onRedirect((String) returned, clazz, method);
        }
      }
    }
  }
}
