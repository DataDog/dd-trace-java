package datadog.trace.instrumentation.servlet.dispatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
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
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.util.Map;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class RequestDispatcherInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
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
    public static AgentScope start(
        @Advice.Origin("#m") final String method,
        @Advice.This final RequestDispatcher dispatcher,
        @Advice.Local("_requestSpan") Object requestSpan,
        @Advice.Argument(0) final ServletRequest request) {
      final AgentSpan parentSpan = activeSpan();

      final Object servletSpanObject = request.getAttribute(DD_SPAN_ATTRIBUTE);
      final AgentSpan servletSpan =
          servletSpanObject instanceof AgentSpan ? (AgentSpan) servletSpanObject : null;

      if (parentSpan == null && servletSpan == null) {
        // Don't want to generate a new top-level span
        return null;
      }
      final AgentSpan.Context parent;
      if (servletSpan == null || (parentSpan != null && servletSpan.isSameTrace(parentSpan))) {
        // Use the parentSpan if the servletSpan is null or part of the same trace.
        parent = parentSpan.context();
      } else {
        // parentSpan is part of a different trace, so lets ignore it.
        // This can happen with the way Tomcat does error handling.
        parent = servletSpan.context();
      }

      final AgentSpan span =
          startSpan(SPAN_NAME_CACHE.computeIfAbsent(method, SERVLET_PREFIX), parent);
      DECORATE.afterStart(span);
      span.setTag(SERVLET_CONTEXT, request.getAttribute(DD_CONTEXT_PATH_ATTRIBUTE));
      span.setTag(SERVLET_PATH, request.getAttribute(DD_SERVLET_PATH_ATTRIBUTE));

      final String target =
          InstrumentationContext.get(RequestDispatcher.class, String.class).get(dispatcher);
      span.setResourceName(target);
      span.setSpanType(InternalSpanTypes.HTTP_SERVER);

      // In case we lose context, inject trace into to the request.
      propagate().inject(span, request, SETTER);
      propagate()
          .injectPathwayContext(
              span, request, SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);

      // temporarily replace from request to avoid spring resource name bubbling up:
      requestSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
      request.setAttribute(DD_SPAN_ATTRIBUTE, span);

      final AgentScope agentScope = activateSpan(span);
      agentScope.setAsyncPropagation(true);
      return agentScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stop(
        @Advice.Enter final AgentScope scope,
        @Advice.Local("_requestSpan") final Object requestSpan,
        @Advice.Argument(0) final ServletRequest request,
        @Advice.Argument(1) final ServletResponse response,
        @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }

      if (requestSpan != null) {
        // now add it back...
        request.setAttribute(DD_SPAN_ATTRIBUTE, requestSpan);
      }

      DECORATE.onError(scope, throwable);
      DECORATE.beforeFinish(scope);
      scope.close();
      scope.span().finish();
    }
  }
}
