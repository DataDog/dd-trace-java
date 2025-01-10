package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Sink;
import datadog.trace.api.iast.VulnerabilityTypes;
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule;
import datadog.trace.api.iast.sink.XssModule;
import net.bytebuddy.asm.Advice;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;
import org.springframework.web.servlet.view.AbstractUrlBasedView;

@AutoService(InstrumenterModule.class)
public final class HandlerMethodReturnValueHandlerCompositeInstrumentation
    extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public HandlerMethodReturnValueHandlerCompositeInstrumentation() {
    super("spring-web");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("selectHandler")),
        HandlerMethodReturnValueHandlerCompositeInstrumentation.class.getName() + "$SpringAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.method.support.HandlerMethodReturnValueHandlerComposite";
  }

  public static class SpringAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Sink(VulnerabilityTypes.SPRING_RESPONSE)
    public static void checkReturnedObject(
        @Advice.Return HandlerMethodReturnValueHandler handler,
        @Advice.Argument(0) final Object value,
        @Advice.Argument(1) MethodParameter returnType) {
      final UnvalidatedRedirectModule unvalidatedRedirectModule =
          InstrumentationBridge.UNVALIDATED_REDIRECT;
      final XssModule xssModule = InstrumentationBridge.XSS;
      if (handler != null && value != null && returnType != null) {
        String clazz = returnType.getMethod().getDeclaringClass().getName();
        String method = returnType.getMethod().getName();
        if (unvalidatedRedirectModule != null && value instanceof AbstractUrlBasedView) {
          unvalidatedRedirectModule.onRedirect(
              ((AbstractUrlBasedView) value).getUrl(), clazz, method);
        } else if (unvalidatedRedirectModule != null && value instanceof ModelAndView) {
          unvalidatedRedirectModule.onRedirect(((ModelAndView) value).getViewName(), clazz, method);
        } else if (value instanceof String) {
          if (xssModule != null && handler instanceof RequestResponseBodyMethodProcessor) {
            xssModule.onXss((String) value, clazz, method);
          } else if (unvalidatedRedirectModule != null) {
            unvalidatedRedirectModule.onRedirect((String) value, clazz, method);
          }
        }
      }
    }
  }
}
