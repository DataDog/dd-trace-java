package datadog.trace.instrumentation.akkahttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;

public final class AkkaPoolMasterActorInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "akka.http.impl.engine.client.PoolMasterActor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // This is how scala names a method that is private to a class but is used in a PartialFunction
    transformer.applyAdvice(
        named("akka$http$impl$engine$client$PoolMasterActor$$startPoolInterface"),
        AkkaPoolMasterActorInstrumentation.class.getName() + "$BlockPropagation");
  }

  /**
   * This instrumentation ensures that the creation of a pool doesn't attach the current {@code
   * Scope} to the {@code onComplete} of the {@code Future} that is only completed when the
   * interface is shut down. This means that the {@code Trace} will finish fast, and not wait for
   * the flush to happen.
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
  }
}
