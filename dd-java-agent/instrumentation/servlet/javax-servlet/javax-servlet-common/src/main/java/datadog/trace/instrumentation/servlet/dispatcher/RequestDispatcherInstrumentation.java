package datadog.trace.instrumentation.servlet.dispatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet.ServletRequestSetter.SETTER;
import static datadog.trace.instrumentation.servlet.SpanNameCache.SERVLET_PREFIX;
import static datadog.trace.instrumentation.servlet.SpanNameCache.SPAN_NAME_CACHE;
import static datadog.trace.instrumentation.servlet.dispatcher.RequestDispatcherDecorator.DD_CONTEXT_PATH_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet.dispatcher.RequestDispatcherDecorator.DD_SERVLET_PATH_ATTRIBUTE;
import static datadog.trace.instrumentation.servlet.dispatcher.RequestDispatcherDecorator.DECORATE;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import java.util.Map;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class RequestDispatcherInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  public RequestDispatcherInstrumentation() {
    super("servlet", "servlet-dispatcher");
  }

  @Override
  public String hierarchyMarkerType() {
    return "javax.servlet.RequestDispatcher";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.servlet.ServletRequestSetter",
      "datadog.trace.instrumentation.servlet.SpanNameCache",
      packageName + ".RequestDispatcherDecorator",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("javax.servlet.RequestDispatcher", String.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        // error is Jetty's method that doesn't delegate to forward or include
        namedOneOf("forward", "include", "error")
            .and(takesArguments(2))
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(isPublic()),
        getClass().getName() + "$RequestDispatcherAdvice");
  }

  public static class RequestDispatcherAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope start(
        @Advice.Origin("#m") final String method,
        @Advice.This final RequestDispatcher dispatcher,
        @Advice.Local("_requestContext") Object requestContext,
        @Advice.Argument(0) final ServletRequest request) {
      final AgentSpan parentSpan = activeSpan();

      final Object contextAttr = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
      final AgentSpan servletSpan =
          contextAttr instanceof Context ? spanFromContext((Context) contextAttr) : null;

      if (parentSpan == null && servletSpan == null) {
        // Don't want to generate a new top-level span
        return null;
      }
      final AgentSpanContext parent;
      if (servletSpan == null || (parentSpan != null && servletSpan.isSameTrace(parentSpan))) {
        // Use the parentSpan if the servletSpan is null or part of the same trace.
        parent = parentSpan.context();
      } else {
        // parentSpan is part of a different trace, so lets ignore it.
        // This can happen with the way Tomcat does error handling.
        parent = servletSpan.context();
      }

      final AgentSpan span =
          startSpan("servlet", SPAN_NAME_CACHE.computeIfAbsent(method, SERVLET_PREFIX), parent);
      DECORATE.afterStart(span);
      span.setTag(SERVLET_CONTEXT, request.getAttribute(DD_CONTEXT_PATH_ATTRIBUTE));
      span.setTag(SERVLET_PATH, request.getAttribute(DD_SERVLET_PATH_ATTRIBUTE));

      final String target =
          InstrumentationContext.get(RequestDispatcher.class, String.class).get(dispatcher);
      span.setResourceName(target);
      span.setSpanType(InternalSpanTypes.HTTP_SERVER);

      // In case we lose context, inject trace into to the request.
      DECORATE.injectContext(span, request, SETTER);

      // temporarily replace from request to avoid spring resource name bubbling up:
      requestContext = request.getAttribute(DD_CONTEXT_ATTRIBUTE);

      final ContextScope scope = getCurrentContext().with(span).attach();
      // Set the context after activation so we have the proper Context object
      request.setAttribute(DD_CONTEXT_ATTRIBUTE, scope.context());

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stop(
        @Advice.Enter final ContextScope scope,
        @Advice.Local("_requestContext") final Object requestContext,
        @Advice.Argument(0) final ServletRequest request,
        @Advice.Argument(1) final ServletResponse response,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      if (requestContext != null) {
        request.setAttribute(DD_CONTEXT_ATTRIBUTE, requestContext);
      }

      final AgentSpan span = spanFromContext(scope.context());
      DECORATE.onError(span, throwable);
      DECORATE.beforeFinish(scope.context());
      scope.close();
      span.finish();
    }
  }
}
