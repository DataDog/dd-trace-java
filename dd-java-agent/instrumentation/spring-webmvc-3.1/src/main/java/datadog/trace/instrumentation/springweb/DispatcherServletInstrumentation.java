package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE_RENDER;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.RESPONSE_RENDER;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import java.util.List;
import net.bytebuddy.asm.Advice;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;

@AutoService(Instrumenter.class)
public final class DispatcherServletInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public DispatcherServletInstrumentation() {
    super("spring-web");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.web.servlet.DispatcherServlet";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebHttpServerDecorator",
      packageName + ".ServletRequestURIAdapter",
      packageName + ".HandlerMappingResourceNameFilter",
      packageName + ".PathMatchingHttpServletRequestWrapper",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("onRefresh"))
            .and(takesArgument(0, named("org.springframework.context.ApplicationContext")))
            .and(takesArguments(1)),
        DispatcherServletInstrumentation.class.getName() + "$HandlerMappingAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(named("render"))
            .and(takesArgument(0, named("org.springframework.web.servlet.ModelAndView"))),
        DispatcherServletInstrumentation.class.getName() + "$RenderAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isProtected())
            .and(nameStartsWith("processHandlerException"))
            .and(takesArgument(3, Exception.class)),
        DispatcherServletInstrumentation.class.getName() + "$ErrorHandlerAdvice");
  }

  /**
   * This advice creates a filter that has reference to the handlerMappings from DispatcherServlet
   * which allows the mappings to be evaluated at the beginning of the filter chain. This evaluation
   * is done inside the Servlet3Decorator.onContext method.
   */
  public static class HandlerMappingAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void afterRefresh(
        @Advice.Argument(0) final ApplicationContext springCtx,
        @Advice.FieldValue("handlerMappings") final List<HandlerMapping> handlerMappings) {
      if (springCtx.containsBean("ddDispatcherFilter")) {
        final HandlerMappingResourceNameFilter filter =
            (HandlerMappingResourceNameFilter) springCtx.getBean("ddDispatcherFilter");
        if (handlerMappings != null && filter != null) {
          filter.setHandlerMappings(handlerMappings);
        }
      }
    }
  }

  public static class RenderAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(0) final ModelAndView mv) {
      final AgentSpan span = startSpan(RESPONSE_RENDER);
      DECORATE_RENDER.afterStart(span);
      DECORATE_RENDER.onRender(span, mv);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      DECORATE_RENDER.onError(scope, throwable);
      DECORATE_RENDER.beforeFinish(scope);
      scope.close();
      scope.span().finish();
    }
  }

  public static class ErrorHandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void nameResource(@Advice.Argument(3) final Exception exception) {
      final AgentSpan span = activeSpan();
      if (span != null && exception != null) {
        boolean alreadyError = span.isError();
        DECORATE.onError(span, exception);
        // We want to capture the stacktrace, but that doesn't mean it should be an error.
        // We rely on a decorator to set the error state based on response code. (5xx -> error)
        // Status code might not be set though if the span isn't the server span.
        // Meaning the error won't be set by the status code. (Probably ok since not measured.)
        span.setError(alreadyError, ErrorPriorities.HTTP_SERVER_DECORATOR);
      }
    }
  }
}
