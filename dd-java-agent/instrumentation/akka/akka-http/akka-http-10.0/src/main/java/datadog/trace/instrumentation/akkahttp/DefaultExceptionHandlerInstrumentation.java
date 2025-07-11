package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.server.ExceptionHandler;
import akka.http.scaladsl.server.ExceptionHandler$;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class DefaultExceptionHandlerInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public DefaultExceptionHandlerInstrumentation() {
    super("akka-http", "akka-http-server");
  }

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.server.ExceptionHandler$";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MarkSpanAsErroredPF",
    };
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
