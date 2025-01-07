package datadog.trace.instrumentation.jakartaws;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jakartaws.WebServiceDecorator.DECORATE;
import static datadog.trace.instrumentation.jakartaws.WebServiceDecorator.JAKARTA_WS_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.jws.WebService;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class WebServiceInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {
  private static final String WEB_SERVICE_ANNOTATION_NAME = "jakarta.jws.WebService";

  public WebServiceInstrumentation() {
    super("jakarta-ws");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(declaresAnnotation(named(WEB_SERVICE_ANNOTATION_NAME)));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".WebServiceDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(not(isStatic()))
            .and(
                hasSuperMethod(
                    isDeclaredBy(declaresAnnotation(named(WEB_SERVICE_ANNOTATION_NAME))))),
        getClass().getName() + "$InvokeAdvice");
  }

  public static final class InvokeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(
        @Advice.This Object thiz, @Advice.Origin("#m") String method) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(WebService.class);
      if (callDepth > 0) {
        return null;
      }

      AgentSpan span = startSpan(JAKARTA_WS_REQUEST);
      span.setMeasured(true);
      DECORATE.onJakartaWsSpan(span, thiz.getClass(), method);
      DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishRequest(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }

      CallDepthThreadLocalMap.reset(WebService.class);

      AgentSpan span = scope.span();
      if (null != error) {
        DECORATE.onError(span, error);
      }
      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
