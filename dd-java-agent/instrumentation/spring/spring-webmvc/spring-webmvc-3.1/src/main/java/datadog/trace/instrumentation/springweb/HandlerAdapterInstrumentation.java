package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getRootContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DD_HANDLER_SPAN_CONTINUE_SUFFIX;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DD_HANDLER_SPAN_PREFIX_KEY;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.web.method.HandlerMethod;

@AutoService(InstrumenterModule.class)
public final class HandlerAdapterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public HandlerAdapterInstrumentation() {
    super("spring-web");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.springframework.web.servlet.HandlerAdapter";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebHttpServerDecorator", packageName + ".ServletRequestURIAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(nameStartsWith("handle"))
            .and(takesArgument(0, named("javax.servlet.http.HttpServletRequest")))
            .and(takesArguments(3)),
        HandlerAdapterInstrumentation.class.getName() + "$ControllerAdvice");
  }

  public static class ControllerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope nameResourceAndStartSpan(
        @Advice.Argument(0) final HttpServletRequest request,
        @Advice.Argument(2) final Object handler,
        @Advice.Local("handlerSpanKey") String handlerSpanKey) {
      handlerSpanKey = "";

      // Name the parent span based on the matching pattern
      Object parentSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
      if (parentSpan instanceof AgentSpan) {
        DECORATE.onRequest((AgentSpan) parentSpan, request, request, getRootContext());
      }

      if (activeSpan() == null) {
        return null;
      }

      // Now create a span for handler/controller execution.

      final String handlerKey;
      if (handler instanceof HandlerMethod) {
        handlerKey = ((HandlerMethod) handler).getBean().getClass().getName();
      } else {
        handlerKey = handler.getClass().getName();
      }
      handlerSpanKey = DD_HANDLER_SPAN_PREFIX_KEY + handlerKey;
      final Object existingSpan = request.getAttribute(handlerSpanKey);
      if (existingSpan instanceof AgentSpan) {
        return activateSpan((AgentSpan) existingSpan);
      }

      final AgentSpan span = startSpan(DECORATE.spanName()).setMeasured(true);
      DECORATE.afterStart(span);
      DECORATE.onHandle(span, handler);
      request.setAttribute(handlerSpanKey, span);
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Argument(0) final HttpServletRequest request,
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable,
        @Advice.Local("handlerSpanKey") String handlerSpanKey) {
      if (scope == null) {
        return;
      }
      boolean finish =
          !Boolean.TRUE.equals(
              request.getAttribute(handlerSpanKey + DD_HANDLER_SPAN_CONTINUE_SUFFIX));
      final AgentSpan span = scope.span();
      scope.close();
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        finish = true;
      }
      if (finish) {
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
