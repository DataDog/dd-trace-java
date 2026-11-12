package datadog.trace.instrumentation.pekko.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.currentContext;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.context.ContextHandle;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class PekkoMailboxInstrumentation extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public PekkoMailboxInstrumentation() {
    super("pekko_actor_mailbox", "pekko_actor", "pekko_concurrent", "java_concurrent");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.dispatch.Mailbox";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("run")), getClass().getName() + "$SuppressMailboxRunAdvice");
  }

  /**
   * This instrumentation is defensive and closes all scopes on the scope stack that were not there
   * when we started processing this actor mailbox. The reason for that is twofold.
   *
   * <p>1) An actor is self contained, and driven by a thread that could serve many other purposes,
   * and a scope should not leak out after a mailbox has been processed.
   *
   * <p>2) We rely on this cleanup mechanism to be able to intentionally leak the scope in the
   * {@code PekkoHttpServerInstrumentation} so that it propagates to the user provided request
   * handling code that will execute on the same thread in the same actor.
   */
  public static final class SuppressMailboxRunAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextHandle enter() {
      if (InstrumenterConfig.get().isLegacyContextManagerEnabled()) {
        // remember the currently active scope so we can roll back to this point
        AgentTracer.get().checkpointActiveForRollback();
        return null;
      } else {
        return currentContext().swap();
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void exit(@Advice.Enter final ContextHandle checkpointContext) {
      if (checkpointContext == null) {
        // Clean up any leaking scopes from pekko-streams/pekko-http etc.
        AgentTracer.get().rollbackActiveToCheckpoint();
      } else {
        checkpointContext.swap();
      }
    }
  }
}
