package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class HttpRequestParserInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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
      AgentSpan span = activeSpan();
      ContextScope scope = null;
      try {
        if (span == null) {
          final Context parentContext = DECORATE.extract(exchange);
          final Context context = DECORATE.startSpan(exchange, parentContext);
          span = spanFromContext(context);
          scope = context.attach();
          DECORATE.afterStart(span);
          DECORATE.onRequest(span, exchange, exchange, parentContext);
        }
        DECORATE.onError(span, throwable);
        // because we know that a http 400 will be thrown
        DECORATE.onResponseStatus(span, 400);
        if (scope != null) {
          DECORATE.beforeFinish(scope.context());
        }
      } finally {
        if (span != null) {
          span.finish();
          if (scope != null) {
            scope.close();
          } else {
            // span was already active, scope will be closed by HandlerInstrumentation
          }
        }
      }
    }
  }
}
