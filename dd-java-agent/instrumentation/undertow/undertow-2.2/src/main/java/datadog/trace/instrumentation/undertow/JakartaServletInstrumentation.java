package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_CONTEXT;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.SERVLET_PATH;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_CONTINUATION;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.SERVLET_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;
import jakarta.servlet.ServletRequest;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class JakartaServletInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JakartaServletInstrumentation() {
    super("undertow", "undertow-2.2");
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
      packageName + ".UndertowBlockResponseFunction",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response",
      packageName + ".IgnoreSendAttribute",
    };
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
        undertowSpan.setTag(SERVLET_PATH, exchange.getRelativePath());
      }
    }
  }
}
