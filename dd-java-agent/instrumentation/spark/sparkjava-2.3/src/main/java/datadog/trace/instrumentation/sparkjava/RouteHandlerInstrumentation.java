package datadog.trace.instrumentation.sparkjava;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.currentSpan;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class RouteHandlerInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "spark.RouteImpl";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("spark.RouteImpl"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("handle")
            .and(isMethod())
            .and(takesArgument(0, named("spark.Request")))
            .and(takesArgument(1, named("spark.Response"))),
        RouteHandlerInstrumentation.class.getName() + "$RouteHandlerAdvice");
  }

  public static class RouteHandlerAdvice {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Thrown final Throwable throwable) {
      if (throwable != null) {
        final AgentSpan span = currentSpan();
        if (span != null) {
          span.addThrowable(throwable);
        }
      }
    }
  }
}
