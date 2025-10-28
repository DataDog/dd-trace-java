package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.iast.IastPostProcessorFactory;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/** Obtain template and matrix variables for AbstractUrlHandlerMapping */
@AutoService(InstrumenterModule.class)
public class TemplateVariablesUrlHandlerInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private Advice.PostProcessor.Factory postProcessorFactory;

  public TemplateVariablesUrlHandlerInstrumentation() {
    super("spring-web");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    if (enabledSystems.contains(TargetSystem.IAST)) {
      postProcessorFactory = IastPostProcessorFactory.INSTANCE;
      return true;
    }
    return enabledSystems.contains(TargetSystem.APPSEC);
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // Only apply to versions of spring-webmvc that include request mapping information
    return hasClassNamed("org.springframework.web.servlet.mvc.method.RequestMappingInfo");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.handler.AbstractUrlHandlerMapping$UriTemplateVariablesHandlerInterceptor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("preHandle"))
            .and(takesArguments(3))
            .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest")))
            .and(takesArgument(1, named("javax.servlet.http.HttpServletResponse")))
            .and(takesArgument(2, Object.class)),
        TemplateVariablesUrlHandlerInstrumentation.class.getName() + "$InterceptorPreHandleAdvice");
  }

  @Override
  public Advice.PostProcessor.Factory postProcessor() {
    return postProcessorFactory;
  }

  public static class InterceptorPreHandleAdvice {
    private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
        "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";

    @SuppressWarnings("Duplicates")
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    @Source(SourceTypes.REQUEST_PATH_PARAMETER)
    public static void after(
        @Advice.Argument(0) final HttpServletRequest req,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      AgentSpan agentSpan = AgentTracer.activeSpan();
      if (agentSpan == null) {
        return;
      }

      Object templateVars = req.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
      if (!(templateVars instanceof Map)) {
        return;
      }

      Map<String, String> map = (Map<String, String>) templateVars;
      if (map.isEmpty()) {
        return;
      }

      RequestContext reqCtx = agentSpan.getRequestContext();
      if (reqCtx == null) {
        return;
      }

      { // appsec
        Object appSecRequestContext = reqCtx.getData(RequestContextSlot.APPSEC);
        if (appSecRequestContext != null) {
          CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
          BiFunction<RequestContext, Map<String, ?>, Flow<Void>> callback =
              cbp.getCallback(EVENTS.requestPathParams());
          if (callback != null) {
            Flow<Void> flow = callback.apply(reqCtx, map);
            Flow.Action action = flow.getAction();
            if (action instanceof Flow.Action.RequestBlockingAction) {
              Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
              BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
              if (brf != null) {
                brf.tryCommitBlockingResponse(
                    reqCtx.getTraceSegment(),
                    rba.getStatusCode(),
                    rba.getBlockingContentType(),
                    rba.getExtraHeaders());
              }
              t =
                  new BlockingException(
                      "Blocked request (for UriTemplateVariablesHandlerInterceptor/preHandle)");
            }
          }
        }
      }

      { // iast
        IastContext iastRequestContext = reqCtx.getData(RequestContextSlot.IAST);
        if (iastRequestContext != null) {
          PropagationModule module = InstrumentationBridge.PROPAGATION;
          if (module != null) {
            for (Map.Entry<String, String> e : map.entrySet()) {
              String parameterName = e.getKey();
              String value = e.getValue();
              if (parameterName == null || value == null) {
                continue; // should not happen
              }
              module.taintString(
                  iastRequestContext, value, SourceTypes.REQUEST_PATH_PARAMETER, parameterName);
            }
          }
        }
      }
    }
  }
}
