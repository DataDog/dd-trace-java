package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.advice.ActiveRequestContext;
import datadog.trace.advice.RequiresRequestContext;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/** Obtain template and matrix variables for AbstractUrlHandlerMapping */
@AutoService(Instrumenter.class)
public class TemplateVariablesUrlHandlerInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public TemplateVariablesUrlHandlerInstrumentation() {
    super("spring-web");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Only apply to versions of spring-webmvc that include request mapping information
    return hasClassNamed("org.springframework.web.servlet.mvc.method.RequestMappingInfo");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping$UriTemplateVariablesHandlerInterceptor";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("preHandle"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest")))
            .and(takesArgument(1, named("javax.servlet.http.HttpServletResponse")))
            .and(takesArgument(2, Object.class)),
        TemplateVariablesUrlHandlerInstrumentation.class.getName() + "$InterceptorPreHandleAdvice");
  }

  @RequiresRequestContext(RequestContextSlot.APPSEC)
  public static class InterceptorPreHandleAdvice {
    private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
        "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";

    @SuppressWarnings("Duplicates")
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(
        @Advice.Argument(0) final HttpServletRequest req,
        @ActiveRequestContext RequestContext reqCtx) {
      Object templateVars = req.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
      if (!(templateVars instanceof Map)) {
        return;
      }

      Map<String, Object> map = (Map<String, Object>) templateVars;
      if (map.isEmpty()) {
        return;
      }

      CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
      BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
          cbp.getCallback(EVENTS.requestPathParams());
      if (callback == null) {
        return;
      }
      callback.apply(reqCtx, map);
    }
  }
}
