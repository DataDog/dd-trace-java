package datadog.trace.instrumentation.pekkohttp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class PekkoPoolMasterActorInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public PekkoPoolMasterActorInstrumentation() {
    super("pekko-http", "pekko-http-client");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.http.impl.engine.client.PoolMasterActor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // This is how scala names a method that is private to a class but is used in a PartialFunction
    transformer.applyAdvice(
        named("org$apache$pekko$http$impl$engine$client$PoolMasterActor$$startPoolInterface"),
        PekkoPoolMasterActorInstrumentation.class.getName() + "$BlockPropagation");
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
