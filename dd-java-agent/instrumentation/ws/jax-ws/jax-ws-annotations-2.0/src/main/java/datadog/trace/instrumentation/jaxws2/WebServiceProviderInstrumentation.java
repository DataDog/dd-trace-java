package datadog.trace.instrumentation.jaxws2;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresAnnotation;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jaxws2.WebServiceProviderDecorator.DECORATE;
import static datadog.trace.instrumentation.jaxws2.WebServiceProviderDecorator.JAX_WS_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.xml.ws.WebServiceProvider;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class WebServiceProviderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {
  private static final String WEB_SERVICE_PROVIDER_INTERFACE_NAME = "javax.xml.ws.Provider";
  private static final String WEB_SERVICE_PROVIDER_ANNOTATION_NAME =
      "javax.xml.ws.WebServiceProvider";

  public WebServiceProviderInstrumentation() {
    super("jax-ws");
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(WEB_SERVICE_PROVIDER_INTERFACE_NAME))
        .and(declaresAnnotation(named(WEB_SERVICE_PROVIDER_ANNOTATION_NAME)));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".WebServiceProviderDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("invoke")).and(takesArguments(1)),
        getClass().getName() + "$InvokeAdvice");
  }

  public static final class InvokeAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(
        @Advice.This Object thiz, @Advice.Origin("#m") String method) {
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(WebServiceProvider.class);
      if (callDepth > 0) {
        return null;
      }

      AgentSpan span = startSpan(JAX_WS_REQUEST);
      span.setMeasured(true);
      DECORATE.onJaxWsSpan(span, thiz.getClass(), method);
      DECORATE.afterStart(span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishRequest(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }

      CallDepthThreadLocalMap.reset(WebServiceProvider.class);

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
