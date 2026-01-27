package datadog.trace.instrumentation.pekko.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.checkpointActiveForRollback;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.rollbackActiveToCheckpoint;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.apache.pekko.dispatch.Envelope;

@AutoService(InstrumenterModule.class)
public class PekkoActorCellInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public PekkoActorCellInstrumentation() {
    super("pekko_actor_receive", "pekko_actor", "pekko_concurrent", "java_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.actor.ActorCell";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.apache.pekko.dispatch.Envelope", State.class.getName());
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
   * {@code PekkoHttpServerInstrumentation} so that it propagates to the user provided request
   * handling code that will execute on the same thread in the same actor.
   */
  public static class InvokeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(@Advice.Argument(value = 0) Envelope envelope) {

      // do this before checkpointing, as the envelope's task scope may already be active
      AgentScope taskScope =
          AdviceUtils.startTaskScope(
              InstrumentationContext.get(Envelope.class, State.class), envelope);

      // remember the currently active scope so we can roll back to this point
      checkpointActiveForRollback();

      return taskScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter AgentScope taskScope) {

      // Clean up any leaking scopes from pekko-streams/pekko-http etc.
      rollbackActiveToCheckpoint();

      // close envelope's task scope if we previously started it
      if (taskScope != null) {
        taskScope.close();
      }
    }
  }
}
