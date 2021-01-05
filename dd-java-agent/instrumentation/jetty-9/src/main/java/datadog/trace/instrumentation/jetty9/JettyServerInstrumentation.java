package datadog.trace.instrumentation.jetty9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty9.JettyDecorator.DECORATE;
import static datadog.trace.instrumentation.jetty9.JettyDecorator.SERVLET_REQUEST;
import static datadog.trace.instrumentation.jetty9.RequestExtractAdapter.GETTER;
import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.DDTags;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;

@AutoService(Instrumenter.class)
public final class JettyServerInstrumentation extends Instrumenter.Tracing {

  public JettyServerInstrumentation() {
    super("jetty");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.eclipse.jetty.server.HttpChannel");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".JettyDecorator",
      packageName + ".RequestExtractAdapter",
      packageName + ".RequestURIDataAdapter",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    final Map<ElementMatcher<? super MethodDescription>, String> transformers = new HashMap<>();
    transformers.put(
        takesNoArguments()
            .and(
                named("handle")
                    .or(
                        // In 9.0.3 the handle logic was extracted out to "handle"
                        // but we still want to instrument run in case handle is missing
                        // (without the risk of double instrumenting).
                        named("run")
                            .and(
                                new ElementMatcher.Junction.AbstractBase<MethodDescription>() {
                                  @Override
                                  public boolean matches(MethodDescription target) {
                                    // TODO this could probably be made into a nicer matcher.
                                    return !declaresMethod(named("handle"))
                                        .matches(target.getDeclaringType().asErasure());
                                  }
                                }))),
        JettyServerInstrumentation.class.getName() + "$HandleAdvice");
    transformers.put(
        // name changed to recycle in 9.3.0
        namedOneOf("reset", "recycle").and(takesNoArguments()),
        JettyServerInstrumentation.class.getName() + "$ResetAdvice");
    return transformers;
  }

  public static class HandleAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final HttpChannel<?> channel) {
      Request req = channel.getRequest();

      Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (existingSpan instanceof AgentSpan) {
        // Request already gone through initial processing, so just activate the span.
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

  /**
   * Jetty ensures that connections are reset immediately after the response is sent. This provides
   * a reliable point to finish the server span at the last possible moment.
   */
  public static class ResetAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final HttpChannel<?> channel) {
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

    private void muzzleCheck(HttpChannel<?> connection) {
      connection.run();
    }
  }
}
