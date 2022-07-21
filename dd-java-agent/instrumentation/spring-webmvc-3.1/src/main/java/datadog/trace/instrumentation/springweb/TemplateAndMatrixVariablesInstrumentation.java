package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.function.BiFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/** Obtain template and matrix variables for RequestMappingInfoHandlerMapping. */
@AutoService(Instrumenter.class)
public class TemplateAndMatrixVariablesInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public TemplateAndMatrixVariablesInstrumentation() {
    super("spring-web");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed(
        "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("handleMatch"))
            .and(
                takesArgument(
                    0, named("org.springframework.web.servlet.mvc.method.RequestMappingInfo")))
            .and(takesArgument(1, String.class))
            .and(takesArgument(2, named("javax.servlet.http.HttpServletRequest")))
            .and(takesArguments(3)),
        TemplateAndMatrixVariablesInstrumentation.class.getName() + "$HandleMatchAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PairList",
    };
  }

  public static class HandleMatchAdvice {
    private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
        "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";
    private static final String MATRIX_VARIABLES_ATTRIBUTE =
        "org.springframework.web.servlet.HandlerMapping.matrixVariables";

    @SuppressWarnings("Duplicates")
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void after(@Advice.Argument(2) final HttpServletRequest req) {
      AgentSpan agentSpan = activeSpan();
      if (agentSpan == null) {
        return;
      }

      Object templateVars = req.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
      Map<String, Object> map = null;
      if (templateVars instanceof Map) {
        map = (Map<String, Object>) templateVars;
      }

      Object matrixVars = req.getAttribute(MATRIX_VARIABLES_ATTRIBUTE);
      if (matrixVars instanceof Map) {
        if (map != null) {
          map = new HashMap<>(map);
          for (Map.Entry<String, Object> e : ((Map<String, Object>) matrixVars).entrySet()) {
            String key = e.getKey();
            Object curValue = map.get(key);
            if (curValue != null) {
              map.put(key, new PairList(curValue, e.getValue()));
            } else {
              map.put(key, e.getValue());
            }
          }
        } else {
          map = (Map<String, Object>) matrixVars;
        }
      }

      if (map != null && !map.isEmpty()) {
        CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
        BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
            cbp.getCallback(EVENTS.requestPathParams());
        RequestContext requestContext = agentSpan.getRequestContext();
        if (requestContext == null || callback == null) {
          return;
        }
        callback.apply(requestContext, map);
      }
    }
  }
}
