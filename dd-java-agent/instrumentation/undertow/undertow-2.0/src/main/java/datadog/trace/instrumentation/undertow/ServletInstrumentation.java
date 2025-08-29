package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_CONTINUATION;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.SERVLET_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletPathMatch;
import io.undertow.servlet.handlers.ServletRequestContext;
import javax.servlet.ServletRequest;
import javax.servlet.http.MappingMatch;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class ServletInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public ServletInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.servlet.handlers.ServletInitialHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("dispatchRequest")), getClass().getName() + "$DispatchAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HttpServerExchangeURIDataAdapter",
      packageName + ".UndertowDecorator",
      packageName + ".UndertowBlockingHandler",
      packageName + ".IgnoreSendAttribute",
      packageName + ".UndertowBlockResponseFunction",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response"
    };
  }

  @Override
  public String muzzleDirective() {
    return "javax.servlet";
  }

  public static class DispatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(0) final HttpServerExchange exchange,
        @Advice.Argument(1) final ServletRequestContext servletRequestContext) {
      AgentScope.Continuation continuation = exchange.getAttachment(DD_UNDERTOW_CONTINUATION);
      if (continuation != null) {
        AgentSpan undertowSpan = continuation.span();
        ServletRequest request = servletRequestContext.getServletRequest();
        request.setAttribute(DD_CONTEXT_ATTRIBUTE, continuation.context());
        undertowSpan.setSpanName(SERVLET_REQUEST);

        undertowSpan.setTag(SERVLET_CONTEXT, request.getServletContext().getContextPath());
        String relativePath = exchange.getRelativePath();

        ServletPathMatch servletPathMatch = servletRequestContext.getServletPathMatch();
        if (UndertowDecorator.UNDERTOW_LEGACY_TRACING
            && servletPathMatch != null
            && servletPathMatch.getMappingMatch() != MappingMatch.DEFAULT) {
          // Set the route unless the mapping match is default, this way we prevent setting route
          // for a non-existing resource. Otherwise, it'd set a non-existing resource name with
          // higher priority than 404 resource, so it wouldn't be able to set resource 404 later in
          // the onResponse instrumentation.
          HTTP_RESOURCE_DECORATOR.withRoute(
              undertowSpan, exchange.getRequestMethod().toString(), relativePath, false);
        }
        // The servlet.path tag is expected even for a non-existing resource.
        undertowSpan.setTag(SERVLET_PATH, relativePath);
      }
    }
  }
}
