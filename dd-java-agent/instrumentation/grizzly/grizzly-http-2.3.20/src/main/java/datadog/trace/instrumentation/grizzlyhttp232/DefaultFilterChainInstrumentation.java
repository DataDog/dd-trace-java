package datadog.trace.instrumentation.grizzlyhttp232;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPrivate;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.glassfish.grizzly.filterchain.FilterChainContext;

public class DefaultFilterChainInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "org.glassfish.grizzly.filterchain.DefaultFilterChain";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPrivate())
            .and(named("notifyFailure"))
            .and(takesArgument(0, named("org.glassfish.grizzly.filterchain.FilterChainContext")))
            .and(takesArgument(1, named("java.lang.Throwable"))),
        "datadog.trace.instrumentation.grizzlyhttp232.DefaultFilterChainAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("executeFilter"))
            .and(takesArgument(2, named("org.glassfish.grizzly.filterchain.FilterChainContext"))),
        getClass().getName() + "$PropagateServerSpanAdvice");
  }

  public static class PropagateServerSpanAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(2) final FilterChainContext ctx) {
      final AgentSpan active = activeSpan();
      // don't activate a span if already one is active
      if (active != null) {
        return null;
      }
      final Object span = ctx.getAttributes().getAttribute(DD_SPAN_ATTRIBUTE);
      if (span instanceof AgentSpan) {
        // activate the http server span when nothing is already active
        return activateSpan((AgentSpan) span);
      }
      return null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
