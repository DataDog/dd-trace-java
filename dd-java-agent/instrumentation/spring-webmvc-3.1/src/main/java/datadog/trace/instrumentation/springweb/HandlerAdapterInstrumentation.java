package datadog.trace.instrumentation.springweb;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.SPRING_HANDLER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class HandlerAdapterInstrumentation extends Instrumenter.Tracing {

  public HandlerAdapterInstrumentation() {
    super("spring-web");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("org.springframework.web.servlet.HandlerAdapter");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("org.springframework.web.servlet.HandlerAdapter"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringWebHttpServerDecorator",
      packageName + ".ServletRequestURIAdapter",
      packageName + ".SpringWebHttpServerDecorator$1"
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
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
        @Advice.Local("_parentSpan") Object parentSpan) {
      // Name the parent span based on the matching pattern
      parentSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
      if (parentSpan instanceof AgentSpan) {
        DECORATE.onRequest((AgentSpan) parentSpan, request);
      }

      if (activeSpan() == null) {
        return null;
      }

      // Now create a span for handler/controller execution.

      final AgentSpan span = startSpan(SPRING_HANDLER);
      span.setMeasured(true);
      DECORATE.afterStart(span);
      DECORATE.onHandle(span, handler);

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Argument(1) HttpServletResponse response,
        @Advice.Local("_parentSpan") Object parentSpan,
        @Advice.Enter final AgentScope scope,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      if (parentSpan instanceof AgentSpan) {
        DECORATE.onResponse((AgentSpan) parentSpan, response);
      }

      DECORATE.onError(scope, throwable);
      DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
    }
  }
}
