package datadog.trace.agent.test.scopediag;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;

/**
 * Installs the test-only diagnostic advice into the tracer's own {@code
 * datadog.trace.core.scopemanager.ScopeContinuation} and {@code datadog.trace.core.PendingTrace}.
 *
 * <p>These classes sit under {@code datadog.trace.core.*}, which the tracer's own {@code
 * AgentBuilder} hard-ignores. We therefore install a <em>separate</em> {@link AgentBuilder} on the
 * raw {@link Instrumentation} with no global-ignore filter, using retransformation so the
 * already-loaded classes are rewoven on install. The advice is schema-preserving, so {@code
 * disableClassFormatChanges()} + {@code REDEFINE} keep it retransform-safe. Installed once per JVM.
 */
final class ScopeContinuationTransformer {
  private static volatile ResettableClassFileTransformer transformer;

  private ScopeContinuationTransformer() {}

  static synchronized void install() {
    if (transformer != null) {
      return;
    }
    Instrumentation instrumentation = ByteBuddyAgent.getInstrumentation();
    transformer =
        new AgentBuilder.Default()
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .type(named("datadog.trace.core.scopemanager.ScopeContinuation"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder
                        .visit(Advice.to(ContinuationAdvice.Register.class).on(named("register")))
                        .visit(Advice.to(ContinuationAdvice.Activate.class).on(named("activate")))
                        .visit(Advice.to(ContinuationAdvice.Cancel.class).on(named("cancel")))
                        .visit(
                            Advice.to(ContinuationAdvice.CancelFromClose.class)
                                .on(named("cancelFromContinuedScopeClose"))))
            .type(named("datadog.trace.core.PendingTrace"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder.visit(
                        Advice.to(PendingTraceAdvice.Write.class)
                            .on(named("write").and(takesArguments(boolean.class)))))
            .installOn(instrumentation);
  }
}
