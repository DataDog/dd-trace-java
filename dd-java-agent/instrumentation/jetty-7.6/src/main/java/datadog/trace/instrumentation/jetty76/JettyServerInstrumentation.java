package datadog.trace.instrumentation.jetty76;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty76.HttpServletRequestExtractAdapter.GETTER;
import static datadog.trace.instrumentation.jetty76.JettyDecorator.DECORATE;
import static datadog.trace.instrumentation.jetty76.JettyDecorator.SERVLET_REQUEST;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDTags;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.http.Generator;
import org.eclipse.jetty.server.AbstractHttpConnection;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

@AutoService(Instrumenter.class)
public final class JettyServerInstrumentation extends Instrumenter.Tracing {

  public JettyServerInstrumentation() {
    super("jetty");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.server.AbstractHttpConnection");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyDecorator",
      packageName + ".HttpServletRequestExtractAdapter",
      packageName + ".RequestURIDataAdapter",
    };
  }

  @Override
  public Map<String, String> contextStore() {
    // The lifecycle of these objects are aligned, and are recycled by jetty, minimizing leak risk.
    return singletonMap("org.eclipse.jetty.http.Generator", "org.eclipse.jetty.server.Response");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        isConstructor(), JettyServerInstrumentation.class.getName() + "$ConstructorAdvice");
    transformers.put(
        named("handleRequest").and(takesNoArguments()),
        JettyServerInstrumentation.class.getName() + "$HandleRequestAdvice");
    transformers.put(
        named("reset").and(takesNoArguments()),
        JettyServerInstrumentation.class.getName() + "$ResetAdvice");
    return transformers;
  }

  // This advice is needed to link the Generator with the Response so we can get the right status
  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void link(
        @Advice.FieldValue("_generator") final Generator generator,
        @Advice.FieldValue("_response") final Response response) {
      InstrumentationContext.get(Generator.class, Response.class).put(generator, response);
    }
  }

  // handleRequest is used instead of handle to allow the incoming request to be fully parsed.
  public static class HandleRequestAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final AbstractHttpConnection connection) {
      Request req = connection.getRequest();

      Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (existingSpan instanceof AgentSpan) {
        // Request already gone through initial processing.
        return activateSpan((AgentSpan) existingSpan);
      }

      final AgentSpan.Context extractedContext = propagate().extract(req, GETTER);

      final AgentSpan span = startSpan(SERVLET_REQUEST, extractedContext).setMeasured(true);
      DECORATE.afterStart(span);
      DECORATE.onConnection(span, req);
      DECORATE.onRequest(span, req);

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      req.setAttribute(DD_SPAN_ATTRIBUTE, span);
      req.setAttribute(CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
      req.setAttribute(CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      scope.close();
    }
  }

  // Working assumption is that all channels get reset rather than GC'd.
  // This should give us the final status code and the broadest span time measurement.
  public static class ResetAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final AbstractHttpConnection channel) {
      Request req = channel.getRequest();
      Response resp = channel.getResponse();

      Object spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (spanObj instanceof AgentSpan) {
        final AgentSpan span = (AgentSpan) spanObj;

        if (Config.get().isServletPrincipalEnabled() && req.getUserPrincipal() != null) {
          span.setTag(DDTags.USER_NAME, req.getUserPrincipal().getName());
        }
        DECORATE.onResponse(span, resp);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
  }
}
