package datadog.trace.instrumentation.akka.init;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.named;

import akka.actor.ActorSystem$;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@Slf4j
@AutoService(Instrumenter.class)
public final class DisableTracingActorInitInstrumentation extends Instrumenter.Default {

  public DisableTracingActorInitInstrumentation() {
    super("akka_concurrent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("akka.actor.ActorSystem$");
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
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
