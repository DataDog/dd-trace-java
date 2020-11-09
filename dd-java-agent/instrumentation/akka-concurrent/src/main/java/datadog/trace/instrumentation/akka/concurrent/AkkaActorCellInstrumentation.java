package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;

import akka.dispatch.Envelope;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class AkkaActorCellInstrumentation extends Instrumenter.Default {

  public AkkaActorCellInstrumentation() {
    super("java_concurrent", "akka_concurrent", "akka_actor");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("akka.actor.ActorCell");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.dispatch.Envelope", State.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(isMethod().and(named("invoke")), getClass().getName() + "$InvokeAdvice");
  }

  public static class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceScope enter(@Advice.Argument(value = 0) Envelope envelope) {
      TraceScope scope =
          AdviceUtils.startTaskScope(
              InstrumentationContext.get(Envelope.class, State.class), envelope);
      if (scope != null) {
        return scope;
      }
      // If there is no scope created from the envelope, we create our own noopSpan to make sure
      // that we can close all scopes up until this position after exit.
      AgentSpan span = new AgentTracer.NoopAgentSpan();
      return activateSpan(span, false);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter TraceScope scope) {
      // Clean up any leaking scopes from akka-streams/akka-http et.c.
      TraceScope activeScope = activeScope();
      while (activeScope != null && activeScope != scope) {
        activeScope.close();
        activeScope = activeScope();
      }
      while (activeScope == scope) {
        scope.close();
        activeScope = activeScope();
      }
    }
  }
}
