package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_SPAN;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.SERVLET_REQUEST;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;
import javax.servlet.ServletRequest;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class ServletInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public ServletInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.servlet.handlers.ServletInitialHandler";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("dispatchRequest")), getClass().getName() + "$DispatchAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HttpServerExchangeURIDataAdapter",
      packageName + ".UndertowDecorator",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response"
    };
  }

  public static class DispatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(0) final HttpServerExchange exchange,
        @Advice.Argument(1) final ServletRequestContext servletRequestContext) {
      AgentSpan undertow_span = exchange.getAttachment(DD_UNDERTOW_SPAN);
      if (null != undertow_span) {
        ServletRequest request = servletRequestContext.getServletRequest();
        request.setAttribute(DD_SPAN_ATTRIBUTE, undertow_span);
        undertow_span.setSpanName(SERVLET_REQUEST);
      }
    }
  }
}
