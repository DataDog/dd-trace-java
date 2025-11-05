package datadog.trace.instrumentation.servlet3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_DISPATCH;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet3.AsyncDispatcherDecorator.DECORATE;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.DD_CONTEXT_PATH_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet3.Servlet3Decorator.DD_SERVLET_PATH_ATTRIBUTE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import javax.servlet.AsyncContext;
import javax.servlet.ServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class AsyncContextInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public AsyncContextInstrumentation() {
    super("servlet", "servlet-3");
  }

  @Override
  public String muzzleDirective() {
    return "servlet-3.x";
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.AsyncContext";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AsyncDispatcherDecorator",
      packageName + ".FinishAsyncDispatchListener",
      packageName + ".HttpServletExtractAdapter",
      packageName + ".HttpServletExtractAdapter$Request",
      packageName + ".HttpServletExtractAdapter$Response",
      packageName + ".Servlet3Decorator",
      packageName + ".ServletRequestURIAdapter",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("dispatch")),
        AsyncContextInstrumentation.class.getName() + "$DispatchAdvice");
  }

  /**
   * When a request is dispatched, we want new request to have propagation headers from its parent
   * request. The parent request's span is later closed by {@code
   * FinishAsyncDispatchListener#onStartAsync}
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
        final AgentSpan parent = (AgentSpan) spanAttr;

        final AgentSpan span = startSpan(SERVLET_DISPATCH, parent.context());
        // This span should get finished by Servlet3Advice
        // However, when using Jetty without servlets (directly org.eclipse.jetty.server.Handler),
        // that's not the case (see jetty's HandleAdvice)
        DECORATE.afterStart(span);

        // These are pulled from attributes because jetty clears them from the request too early.
        span.setTag(SERVLET_CONTEXT, request.getAttribute(DD_CONTEXT_PATH_ATTRIBUTE));
        span.setTag(SERVLET_PATH, request.getAttribute(DD_SERVLET_PATH_ATTRIBUTE));

        synchronized (request) {
          request.setAttribute(DD_DISPATCH_SPAN_ATTRIBUTE, span);
        }

        if (args.length == 1 && args[0] instanceof String) {
          span.setResourceName((String) args[0]);
        } else if (args.length == 2 && args[1] instanceof String) {
          span.setResourceName((String) args[1]);
        }

        // We can't register FinishAsyncDispatchListener here.
        // The dispatch may happen on an onTimeout/onError, and adding listeners
        // when listeners are being iterated on causes a ConcurrentModificationException on jetty
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
