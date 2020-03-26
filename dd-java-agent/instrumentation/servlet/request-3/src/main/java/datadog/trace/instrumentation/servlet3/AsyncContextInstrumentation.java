package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet3.HttpServletRequestInjectAdapter.SETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Map;
import javax.servlet.AsyncContext;
import javax.servlet.ServletRequest;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class AsyncContextInstrumentation extends Instrumenter.Default {

  public AsyncContextInstrumentation() {
    super("servlet", "servlet-3");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("javax.servlet.AsyncContext");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("javax.servlet.AsyncContext"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".HttpServletRequestInjectAdapter"};
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod().and(isPublic()).and(named("dispatch")),
        AsyncContextInstrumentation.class.getName() + "$DispatchAdvice");
  }

  /**
   * When a request is dispatched, we want new request to have propagation headers from its parent
   * request. The parent request's span is later closed by {@code
   * TagSettingAsyncListener#onStartAsync}
   */
  public static class DispatchAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static boolean enter(
        @Advice.This final AsyncContext context, @Advice.AllArguments final Object[] args) {
      final int depth = CallDepthThreadLocalMap.incrementCallDepth(AsyncContext.class);
      if (depth > 0) {
        return false;
      }

      final ServletRequest request = context.getRequest();
      final Object spanAttr = request.getAttribute(DD_SPAN_ATTRIBUTE);
      if (spanAttr instanceof AgentSpan) {
        request.removeAttribute(DD_SPAN_ATTRIBUTE);
        final AgentSpan span = (AgentSpan) spanAttr;
        // Override propagation headers by injecting attributes from the current span
        // into the new request
        if (request instanceof HttpServletRequest) {
          propagate().inject(span, (HttpServletRequest) request, SETTER);
        }
        final String path;
        if (args.length == 1 && args[0] instanceof String) {
          path = (String) args[0];
        } else if (args.length == 2 && args[1] instanceof String) {
          path = (String) args[1];
        } else {
          path = "true";
        }
        span.setTag("servlet.dispatch", path);
      }
      return true;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final boolean topLevel) {
      if (topLevel) {
        CallDepthThreadLocalMap.reset(AsyncContext.class);
      }
    }
  }
}
