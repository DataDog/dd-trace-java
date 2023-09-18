package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class HttpRequestParserInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.Tracing.ForTypeHierarchy {
  public HttpRequestParserInstrumentation() {
    super("undertow", "undertow-2.2", "undertow-request-parse");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.undertow.server.protocol.http.HttpRequestParser";
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
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("handle"))
            .and(takesArgument(2, named("io.undertow.server.HttpServerExchange"))),
        getClass().getName() + "$RequestParseFailureAdvice");
  }

  public static class RequestParseFailureAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void afterRequestParse(
        @Advice.Argument(2) final HttpServerExchange exchange,
        @Advice.Thrown final Throwable throwable) {
      if (throwable == null) {
        return;
      }
      // if we have an exception here the subsequent instrumentations won't have any chance to open
      // a span
      // this because undertow will just write down a http 400 raw response over the net channel.
      // Here we try to create a span to record this
      AgentScope scope = activeScope();
      AgentSpan span = null;
      try {
        if (scope != null) {
          span = scope.span();
        } else {
          final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(exchange);
          span = DECORATE.startSpan(exchange, extractedContext).setMeasured(true);
          scope = activateSpan(span);
          DECORATE.afterStart(span);
          DECORATE.onRequest(span, exchange, exchange, extractedContext);
        }
        DECORATE.onError(span, throwable);
        // because we know that a http 400 will be thrown
        DECORATE.onResponseStatus(span, 400);
        DECORATE.beforeFinish(span);
      } finally {
        if (span != null) {
          span.finish();
        }
        if (scope != null) {
          scope.close();
        }
      }
    }
  }
}
