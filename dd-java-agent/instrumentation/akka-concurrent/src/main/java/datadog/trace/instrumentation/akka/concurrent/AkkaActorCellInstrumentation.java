package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import akka.dispatch.Envelope;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class AkkaActorCellInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public AkkaActorCellInstrumentation() {
    super("akka_actor_receive", "akka_actor", "akka_concurrent", "java_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "akka.actor.ActorCell";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("akka.dispatch.Envelope", State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("invoke")), getClass().getName() + "$InvokeAdvice");
  }

  /**
   * This instrumentation is defensive and closes all scopes on the scope stack that were not there
   * when we started processing this actor message. The reason for that is twofold.
   *
   * <p>1) An actor is self contained, and driven by a thread that could serve many other purposes,
   * and a scope should not leak out after a message has been processed.
   *
   * <p>2) We rely on this cleanup mechanism to be able to intentionally leak the scope in the
   * {@code AkkaHttpServerInstrumentation} so that it propagates to the user provided request
   * handling code that will execute on the same thread in the same actor.
   */
  public static class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(value = 0) Envelope envelope,
        @Advice.Local(value = "localScope") AgentScope localScope) {
      AgentScope activeScope = activeScope();
      localScope =
          AdviceUtils.startTaskScope(
              InstrumentationContext.get(Envelope.class, State.class), envelope);
      // There was a scope created from the envelop, so use that
      if (localScope != null) {
        return activeScope;
      }
      // If there is no active scope, we can clean all the way to the bottom
      if (null == activeScope) {
        return null;
      }
      // If there is a noop span in the active scope, we can clean all the way to this scope
      if (activeSpan() instanceof AgentTracer.NoopAgentSpan) {
        return activeScope;
      }
      // Create an active scope with a noop span, and clean all the way to the previous scope
      localScope = activateSpan(AgentTracer.NoopAgentSpan.INSTANCE, false);
      return activeScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter AgentScope scope, @Advice.Local(value = "localScope") AgentScope localScope) {
      if (localScope != null) {
        // then we have invoked an Envelope and need to mark the work complete
        localScope.close();
      }
      // Clean up any leaking scopes from akka-streams/akka-http et.c.
      AgentScope activeScope = activeScope();
      while (activeScope != null && activeScope != scope) {
        activeScope.close();
        activeScope = activeScope();
      }
    }
  }
}
