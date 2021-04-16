package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DD_EXTRACTED_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.DECORATE;
import static datadog.trace.instrumentation.liberty20.LibertyDecorator.SERVLET_REQUEST;
import static datadog.trace.instrumentation.liberty20.RequestExtractAdapter.GETTER;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.ibm.ws.webcontainer.srt.SRTServletRequest;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.util.Map;
import javax.servlet.ServletRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class LibertyServerInstrumentation extends Instrumenter.Tracing {

  public LibertyServerInstrumentation() {
    super("liberty");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.ibm.ws.webcontainer.webapp.WebApp");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".LibertyDecorator",
      packageName + ".RequestExtractAdapter",
      packageName + ".RequestURIDataAdapter",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        isMethod()
            .and(named("handleRequest"))
            .and(takesArgument(0, named("javax.servlet.ServletRequest")))
            .and(takesArgument(1, named("javax.servlet.ServletResponse")))
            .and(takesArgument(2, named("com.ibm.wsspi.http.HttpInboundConnection"))),
        LibertyServerInstrumentation.class.getName() + "$HandleRequestAdvice");
  }

  public static class HandleRequestAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(value = 0) ServletRequest req) {
      if (!(req instanceof SRTServletRequest)) return null;
      SRTServletRequest request = (SRTServletRequest) req;

      // if we try to get an attribute that doesn't exist open liberty might complain with an
      // exception
      try {
        Object existingSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
        if (existingSpan instanceof AgentSpan) {
          return activateSpan((AgentSpan) existingSpan);
        }
      } catch (NullPointerException e) {
      }

      final AgentSpan.Context.Extracted extractedContext = propagate().extract(request, GETTER);
      request.setAttribute(DD_EXTRACTED_CONTEXT_ATTRIBUTE, extractedContext);
      final AgentSpan span =
          AgentTracer.startSpan(SERVLET_REQUEST, extractedContext).setMeasured(true);

      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request, request, extractedContext);
      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      request.setAttribute(DD_SPAN_ATTRIBUTE, span);
      request.setAttribute(CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
      request.setAttribute(CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(
        @Advice.Enter final AgentScope scope, @Advice.Argument(value = 0) ServletRequest req) {
      if (!(req instanceof SRTServletRequest)) return;
      SRTServletRequest request = (SRTServletRequest) req;

      if (scope != null) {
        // we cannot get path at the start because the path/context attributes are not yet
        // initialized
        DECORATE.getPath(scope.span(), request);
        scope.close();
      }
    }
  }
}
