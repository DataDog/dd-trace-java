package datadog.trace.instrumentation.sparkjava;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.sparkjava.SparkJavaDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import javax.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

public class JettyHandlerInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "spark.webserver.JettyHandler";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("doHandle")
            .and(takesArgument(0, String.class))
            .and(
                takesArgument(
                    1, named("org.eclipse.jetty.server.Request")))
            .and(takesArgument(2, named("javax.servlet.http.HttpServletRequest")))
            .and(takesArgument(3, named("javax.servlet.http.HttpServletResponse"))),
        JettyHandlerInstrumentation.class.getName() + "$JettyHandlerAdvice");
  }

  public static class JettyHandlerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.Argument(2) final HttpServletRequest request) {
      final Context parentContext = DECORATE.extract(request);
      return parentContext.attach();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
