package datadog.trace.instrumentation.akka.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import akka.dispatch.Envelope;
import com.google.auto.service.AutoService;
import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge;
import datadog.trace.bootstrap.instrumentation.java.concurrent.AdviceUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class AkkaActorCellInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

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
    public static Context enter(
        @Advice.Argument(value = 0) Envelope envelope,
        @Advice.Local("taskScope") AgentScope taskScope) {

      // do this before checkpointing, as the envelope's task scope may already be active
      taskScope =
          AdviceUtils.startTaskScope(
              InstrumentationContext.get(Envelope.class, State.class), envelope);

      // Use swap to checkpoint the active context so we can roll back to this point
      return Java8BytecodeBridge.getCurrentContext().swap();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(
        @Advice.Enter Context checkpoint, @Advice.Local("taskScope") AgentScope taskScope) {

      // Clean up any leaking scopes from akka-streams/akka-http etc.
      checkpoint.swap();

      // close envelope's task scope if we previously started it
      if (taskScope != null) {
        taskScope.close();
      }
    }
  }
}
