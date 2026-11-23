package datadog.trace.instrumentation.servlet5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_RUM_INJECTED;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.rum.RumControllableResponse;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class RumServletInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public RumServletInstrumentation() {
    super("servlet", "servlet-5");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && InstrumenterConfig.get().isRumEnabled();
  }

  @Override
  public int order() {
    // Nest RUM inside tracing: drain the response before tracing extracts the principal on exit.
    return 1;
  }

  @Override
  public String hierarchyMarkerType() {
    return "jakarta.servlet.Servlet";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
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
        getClass().getName() + "$RumServletAdvice");
  }

  public static class RumServletAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static RumControllableResponse before(
        @Advice.Argument(value = 0, readOnly = false) ServletRequest request,
        @Advice.Argument(value = 1, readOnly = false) ServletResponse response,
        @Advice.Local("rumEntered") boolean entered) {
      if (!(request instanceof HttpServletRequest)
          || !(response instanceof HttpServletResponse)
          || !RumInjector.get().isEnabled()) {
        return null;
      }
      // RUM nesting is independent of the HttpServlet-only tracing guard.
      final int depth = CallDepthThreadLocalMap.incrementCallDepth(Servlet.class);
      entered = true;
      if (depth > 0) {
        return null;
      }
      final HttpServletRequest httpRequest = (HttpServletRequest) request;
      final Object existing = httpRequest.getAttribute(DD_RUM_INJECTED);
      if (existing instanceof RumControllableResponse) {
        // Async redispatch can reuse a wrapper created by startAsync on another thread.
        return (RumControllableResponse) existing;
      }
      final RumHttpServletResponseWrapper wrapper =
          new RumHttpServletResponseWrapper(httpRequest, (HttpServletResponse) response);
      httpRequest.setAttribute(DD_RUM_INJECTED, wrapper);
      response = wrapper;
      request = new RumHttpServletRequestWrapper(httpRequest, wrapper);
      return wrapper;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(
        @Advice.Enter final RumControllableResponse wrapper,
        @Advice.Local("rumEntered") boolean entered) {
      if (entered) {
        CallDepthThreadLocalMap.decrementCallDepth(Servlet.class);
      }
      if (wrapper != null) {
        wrapper.commit();
      }
    }
  }
}
