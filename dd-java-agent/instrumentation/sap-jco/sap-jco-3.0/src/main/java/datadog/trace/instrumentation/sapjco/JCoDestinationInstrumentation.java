package datadog.trace.instrumentation.sapjco;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;

import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.asm.Advice;

/**
 * Registers the client passport manager when the application resolves a destination ({@code
 * JCoDestinationManager.getDestination}), which always happens before any RFC — including the
 * metadata lookup and connection ping. Without this, the manager is registered only on the first
 * {@code JCoFunction.execute}, so a cold call's metadata/ping (and a lookup for a non-existent
 * function, which never reaches execute) would go out before any manager exists and carry no
 * passport.
 */
public class JCoDestinationInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "com.sap.conn.jco.JCoDestinationManager";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isStatic()).and(named("getDestination")),
        getClass().getName() + "$RegisterAdvice");
  }

  public static class RegisterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      PassportRegistry.ensureClientRegistered();
    }
  }
}
