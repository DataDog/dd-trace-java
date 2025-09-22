package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.hasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_RUM_INJECTED;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.ClassloaderConfigurationOverrides;
import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.rum.RumControllableResponse;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JakartaServletInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public JakartaServletInstrumentation() {
    super("servlet", "servlet-5");
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.http.HttpServlet";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RumHttpServletRequestWrapper",
      packageName + ".RumHttpServletResponseWrapper",
      packageName + ".WrappedServletOutputStream",
    };
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return hasSuperType(named(hierarchyMarkerType()))
        .or(implementsInterface(named("jakarta.servlet.FilterChain")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("service"))
            .and(isPublic())
            .and(takesArguments(2))
            .and(takesArgument(0, named("jakarta.servlet.ServletRequest")))
            .and(takesArgument(1, named("jakarta.servlet.ServletResponse"))),
        getClass().getName() + "$JakartaServletAdvice");
  }

  public static class JakartaServletAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentSpan before(
        @Advice.Argument(value = 0, readOnly = false) ServletRequest request,
        @Advice.Argument(value = 1, readOnly = false) ServletResponse response,
        @Advice.Local("rumServletWrapper") RumControllableResponse rumServletWrapper) {
      if (!(request instanceof HttpServletRequest)) {
        return null;
      }

      if (response instanceof HttpServletResponse) {
        final HttpServletRequest httpServletRequest = (HttpServletRequest) request;

        if (RumInjector.get().isEnabled()) {
          final Object maybeRumWrapper = httpServletRequest.getAttribute(DD_RUM_INJECTED);
          if (maybeRumWrapper instanceof RumControllableResponse) {
            rumServletWrapper = (RumControllableResponse) maybeRumWrapper;
          } else {
            rumServletWrapper =
                new RumHttpServletResponseWrapper(
                    httpServletRequest, (HttpServletResponse) response);
            httpServletRequest.setAttribute(DD_RUM_INJECTED, rumServletWrapper);
            response = (ServletResponse) rumServletWrapper;
            request =
                new RumHttpServletRequestWrapper(
                    httpServletRequest, (HttpServletResponse) rumServletWrapper);
          }
        }
      }

      Object span = request.getAttribute(DD_SPAN_ATTRIBUTE);
      if (span instanceof AgentSpan
          && CallDepthThreadLocalMap.incrementCallDepth(HttpServletRequest.class) == 0) {
        final AgentSpan agentSpan = (AgentSpan) span;
        ClassloaderConfigurationOverrides.maybeEnrichSpan(agentSpan);
        return agentSpan;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Enter final AgentSpan span,
        @Advice.Argument(0) final ServletRequest request,
        @Advice.Local("rumServletWrapper") RumControllableResponse rumServletWrapper) {
      if (span == null) {
        return;
      }
      if (rumServletWrapper != null) {
        rumServletWrapper.commit();
      }

      CallDepthThreadLocalMap.reset(HttpServletRequest.class);
      final HttpServletRequest httpServletRequest =
          (HttpServletRequest) request; // at this point the cast should be safe
      if (Config.get().isServletPrincipalEnabled()
          && httpServletRequest.getUserPrincipal() != null) {
        span.setTag(DDTags.USER_NAME, httpServletRequest.getUserPrincipal().getName());
      }
    }
  }
}
