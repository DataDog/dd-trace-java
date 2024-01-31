package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.undertow.server.HttpServerExchange;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class UndertowInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public UndertowInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.server.HttpServerExchange";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("dispatch"))
            .and(takesArgument(0, named("java.util.concurrent.Executor")))
            .and(takesArgument(1, named("java.lang.Runnable"))),
        getClass().getName() + "$DispatchAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExchangeEndSpanListener",
      packageName + ".HttpServerExchangeURIDataAdapter",
      packageName + ".UndertowDecorator",
      packageName + ".UndertowBlockingHandler",
      packageName + ".IgnoreSendAttribute",
      packageName + ".UndertowBlockResponseFunction",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response",
      packageName + ".UndertowRunnableWrapper"
    };
  }

  public static class DispatchAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void dispatchEnter(
        @Advice.Argument(value = 1, readOnly = false) Runnable task,
        @Advice.This final HttpServerExchange current) {
      task = UndertowRunnableWrapper.wrapIfNeeded(task, current);
    }
  }
}
