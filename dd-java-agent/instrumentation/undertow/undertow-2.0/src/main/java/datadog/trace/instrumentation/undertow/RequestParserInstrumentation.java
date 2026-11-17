package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

@AutoService(InstrumenterModule.class)
public class RequestParserInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public RequestParserInstrumentation() {
    super("undertow", "undertow-2.2", "undertow-request-parse");
  }

  @Override
  public String instrumentedType() {
    // new class added in new minor versions in
    // https://github.com/undertow-io/undertow/pull/1949/changes
    // from 2.2.40, 2.3.25 and 2.4.0
    // not instrumenting the hierarchy like in its predecessor HttpRequestParserInstrumentation
    // because RequestParser is marked final.
    return "io.undertow.server.protocol.http.RequestParser";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("handle"))
            .and(takesArgument(2, named("io.undertow.server.HttpServerExchange"))),
        HttpRequestParserInstrumentation.class.getName() + "$RequestParseFailureAdvice");
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
}
