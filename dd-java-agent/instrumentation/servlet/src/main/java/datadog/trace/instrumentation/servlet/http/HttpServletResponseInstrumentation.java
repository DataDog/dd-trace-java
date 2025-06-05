package datadog.trace.instrumentation.servlet.http;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.servlet.http.HttpServletResponseDecorator.DECORATE;
import static datadog.trace.instrumentation.servlet.http.HttpServletResponseDecorator.SERVLET_RESPONSE;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class HttpServletResponseInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public HttpServletResponseInstrumentation() {
    super("servlet", "servlet-response");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.http.HttpServletResponse";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.ServletRequestSetter",
      packageName + ".HttpServletResponseDecorator",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        namedOneOf("sendError", "sendRedirect"),
        HttpServletResponseInstrumentation.class.getName() + "$SendAdvice");
  }

  public static class SendAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope start(
        @Advice.Origin("#m") final String method, @Advice.This final HttpServletResponse resp) {
      if (activeSpan() == null) {
        // Don't want to generate a new top-level span
        return null;
      }

      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(HttpServletResponse.class);
      if (callDepth > 0) {
        return null;
      }

      final AgentSpan span = startSpan(SERVLET_RESPONSE);
      DECORATE.afterStart(span);

      span.setResourceName(DECORATE.spanNameForMethod(HttpServletResponse.class, method));

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      CallDepthThreadLocalMap.reset(HttpServletResponse.class);

      DECORATE.onError(scope, throwable);
      DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
    }
  }
}
