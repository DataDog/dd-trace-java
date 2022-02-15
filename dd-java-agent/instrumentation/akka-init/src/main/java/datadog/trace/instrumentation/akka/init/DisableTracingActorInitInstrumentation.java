package datadog.trace.instrumentation.akka.init;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import akka.actor.ActorSystem$;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class DisableTracingActorInitInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public DisableTracingActorInitInstrumentation() {
    super("akka_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "akka.actor.ActorSystem$";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("apply"),
        DisableTracingActorInitInstrumentation.class.getName() + "$BlockPropagation");
  }

  /**
   * This instrumentation was added to ensure that the play 2.3 test doesn't hang on the first
   * request. (Without this it propagates the trace into the lazy akka initialization.)
   */
  public static class BlockPropagation {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter() {
      return activateSpan(noopSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final AgentScope scope) {
      scope.close();
    }

    public static void muzzleCheck(final ActorSystem$ actorSystem) {
      actorSystem.apply();
    }
  }
}
