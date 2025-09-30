package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.api.gateway.Events.EVENTS;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

/** Obtain template and matrix variables for RequestMappingInfoHandlerMapping. */
@AutoService(InstrumenterModule.class)
public class TemplateAndMatrixVariablesInstrumentation extends InstrumenterModule
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private Advice.PostProcessor.Factory postProcessorFactory;

  public TemplateAndMatrixVariablesInstrumentation() {
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
    return "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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

  @Override
  public Advice.PostProcessor.Factory postProcessor() {
    return postProcessorFactory;
  }

  public static class HandleMatchAdvice {
    private static final String URI_TEMPLATE_VARIABLES_ATTRIBUTE =
        "org.springframework.web.servlet.HandlerMapping.uriTemplateVariables";
    private static final String MATRIX_VARIABLES_ATTRIBUTE =
        "org.springframework.web.servlet.HandlerMapping.matrixVariables";

    @SuppressWarnings("Duplicates")
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    @Source(SourceTypes.REQUEST_MATRIX_PARAMETER)
    public static void after(
        @Advice.Argument(2) final HttpServletRequest req,
        @Advice.Thrown(readOnly = false) Throwable t) {
      if (t != null) {
        return;
      }
      // hacky, but APM instrumentation causes the instrumented method to be called twice
      if (req.getClass()
          .getName()
          .equals(
              "datadog.trace.instrumentation.springweb.PathMatchingHttpServletRequestWrapper")) {
        return;
      }

      AgentSpan agentSpan = AgentTracer.activeSpan();
      if (agentSpan == null) {
        return;
      }

      Object templateVars = req.getAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE);
      Object matrixVars = req.getAttribute(MATRIX_VARIABLES_ATTRIBUTE);
      if (templateVars == null && matrixVars == null) {
        return;
      }

      RequestContext reqCtx = agentSpan.getRequestContext();
      if (reqCtx == null) {
        return;
      }

      { // appsec
        Object appSecRequestContext = reqCtx.getData(RequestContextSlot.APPSEC);
        if (appSecRequestContext != null) {

          // merge the uri template and matrix variables
          Map<String, Object> map = null;
          if (templateVars instanceof Map) {
            map = (Map<String, Object>) templateVars;
          }
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
                        "Blocked request (for RequestMappingInfoHandlerMapping/handleMatch)");
              }
            }
          }
        }
      }

      { // iast
        IastContext iastRequestContext = reqCtx.getData(RequestContextSlot.IAST);
        if (iastRequestContext != null) {
          PropagationModule module = InstrumentationBridge.PROPAGATION;
          if (module != null) {
            if (templateVars instanceof Map) {
              for (Map.Entry<String, String> e : ((Map<String, String>) templateVars).entrySet()) {
                String parameterName = e.getKey();
                String value = e.getValue();
                if (parameterName == null || value == null) {
                  continue; // should not happen
                }
                module.taintString(
                    iastRequestContext, value, SourceTypes.REQUEST_PATH_PARAMETER, parameterName);
              }
            }

            if (matrixVars instanceof Map) {
              for (Map.Entry<String, Map<String, Iterable<String>>> e :
                  ((Map<String, Map<String, Iterable<String>>>) matrixVars).entrySet()) {
                String parameterName = e.getKey();
                Map<String, Iterable<String>> value = e.getValue();
                if (parameterName == null || value == null) {
                  continue;
                }

                for (Map.Entry<String, Iterable<String>> ie : value.entrySet()) {
                  String innerKey = ie.getKey();
                  if (innerKey != null) {
                    module.taintString(
                        iastRequestContext,
                        innerKey,
                        SourceTypes.REQUEST_MATRIX_PARAMETER,
                        parameterName);
                  }
                  Iterable<String> innerValues = ie.getValue();
                  if (innerValues != null) {
                    for (String iv : innerValues) {
                      module.taintString(
                          iastRequestContext,
                          iv,
                          SourceTypes.REQUEST_MATRIX_PARAMETER,
                          parameterName);
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
