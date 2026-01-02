package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.server.ExceptionHandler;
import akka.http.scaladsl.server.ExceptionHandler$;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

public class DefaultExceptionHandlerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.ExceptionHandler$";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(returns(named("akka.http.scaladsl.server.ExceptionHandler")))
            .and(takesArguments(1))
            .and(takesArgument(0, named("akka.http.scaladsl.settings.RoutingSettings"))),
        DefaultExceptionHandlerInstrumentation.class.getName() + "$DefaultHandlerAdvice");
  }

  static class DefaultHandlerAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.This ExceptionHandler$ eh, @Advice.Return(readOnly = false) ExceptionHandler ret) {
      ret = eh.apply(MarkSpanAsErroredPF.INSTANCE).withFallback(ret);
    }
  }
}
