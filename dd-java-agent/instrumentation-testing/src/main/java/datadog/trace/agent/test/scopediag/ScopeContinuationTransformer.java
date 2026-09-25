package datadog.trace.agent.test.scopediag;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;

/**
 * Installs test-only advice with a separate {@link AgentBuilder} because the tracer ignores its own
 * core classes. Retransformation covers classes loaded before the diagnostic starts.
 *
 * <p>The advice relies on the following lifecycle contract in dd-trace-core. When moving or
 * changing these operations, update their matchers and advice together:
 *
 * <ul>
 *   <li>{@code ScopeContinuation.register()} returns the registered continuation; its normal exit
 *       records capture before the caller can resume or release it.
 *   <li>{@code ScopeContinuation.resume()} returns a scope, or {@code NoopScope.INSTANCE} when
 *       activation fails. Entry supplies the activation timestamp because same-span reuse can
 *       resolve the continuation before this method returns.
 *   <li>{@code ScopeContinuation.release()} and {@code cancelFromContinuedScopeClose()} expose
 *       resolution through their {@code count} field. The probe compares entry and exit counts with
 *       {@code CANCELLED}; a scope close's nested release belongs to that close, not a second
 *       resolution. A hold or outstanding activation can keep the continuation unresolved.
 *   <li>{@code ScopeStack.push(ContinuableScope)} receives a scope whose context, source, and
 *       optional continuation are already available. Entry records a close-owned scope; swapping
 *       context alone must not create one. {@code ContinuableScope.onProperClose()} marks completed
 *       cleanup.
 *   <li>{@code ContinuableScope.close()} is inspected before it changes the stack. A scope that is
 *       not on top is recorded as an out-of-order or wrong-thread close attempt.
 *   <li>{@code ContinuableScopeManager.scheduleRootIterationScopeCleanup(ScopeStack,
 *       ContinuableScope)} transfers cleanup responsibility to the iteration cleaner. Its normal
 *       exit marks the scope as having deferred cleanup.
 *   <li>{@code PendingTrace.write(boolean)} exposes {@code rootSpanWritten} and {@code traceId}. A
 *       non-partial write that changes the flag from false to true records the root write on normal
 *       exit; partial writes and already-written roots do not produce another event.
 * </ul>
 *
 * <p>Keep the reflective member checks in {@code ScopeContinuationProbeTest} and the real-tracer
 * assertions in {@code ScopeDiagnosticsIntegrationTest} aligned with this contract. Member
 * existence alone does not prove advice was applied or still observes the right lifecycle boundary.
 * Tests for an observed event must assert its presence: an empty report can otherwise look
 * leak-free.
 */
final class ScopeContinuationTransformer {
  private static volatile ResettableClassFileTransformer transformer;

  private ScopeContinuationTransformer() {}

  static synchronized void install() {
    if (transformer != null) {
      return;
    }
    try {
      // Related core types can otherwise load these targets reentrantly while they are transformed.
      ClassLoader loader = ScopeContinuationTransformer.class.getClassLoader();
      Class.forName("datadog.trace.core.scopemanager.ScopeContinuation", false, loader);
      Class.forName("datadog.trace.core.scopemanager.ScopeStack", false, loader);
    } catch (ClassNotFoundException missingCoreTracer) {
      throw new IllegalStateException(
          "Scope continuation diagnostics require dd-trace-core", missingCoreTracer);
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
                        .visit(
                            Advice.to(ContinuationAdvice.Register.class)
                                .on(
                                    isMethod()
                                        .and(named("register"))
                                        .and(takesArguments(0))
                                        .and(
                                            returns(
                                                named(
                                                    "datadog.trace.core.scopemanager.ScopeContinuation")))))
                        .visit(
                            Advice.to(ContinuationAdvice.Activate.class)
                                .on(
                                    isMethod()
                                        .and(named("resume"))
                                        .and(takesArguments(0))
                                        .and(returns(named("datadog.context.ContextScope")))))
                        .visit(
                            Advice.to(ContinuationAdvice.Cancel.class)
                                .on(
                                    isMethod()
                                        .and(
                                            named("release")
                                                .or(named("cancelFromContinuedScopeClose")))
                                        .and(takesArguments(0))
                                        .and(returns(void.class)))))
            .type(named("datadog.trace.core.PendingTrace"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder.visit(
                        Advice.to(PendingTraceAdvice.Write.class)
                            .on(
                                isMethod()
                                    .and(named("write"))
                                    .and(takesArguments(boolean.class))
                                    .and(returns(int.class)))))
            .type(named("datadog.trace.core.scopemanager.ContinuableScope"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder
                        .visit(
                            Advice.to(ContinuableScopeAdvice.OnProperClose.class)
                                .on(
                                    isMethod()
                                        .and(named("onProperClose"))
                                        .and(takesArguments(0))
                                        .and(returns(void.class))))
                        .visit(
                            Advice.to(ContinuableScopeAdvice.Close.class)
                                .on(
                                    isMethod()
                                        .and(named("close"))
                                        .and(takesArguments(0))
                                        .and(returns(void.class)))))
            .type(named("datadog.trace.core.scopemanager.ScopeStack"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder.visit(
                        Advice.to(ScopeStackAdvice.Push.class)
                            .on(
                                isMethod()
                                    .and(named("push"))
                                    .and(takesArguments(1))
                                    .and(
                                        takesArgument(
                                            0,
                                            named(
                                                "datadog.trace.core.scopemanager.ContinuableScope")))
                                    .and(returns(void.class)))))
            .type(named("datadog.trace.core.scopemanager.ContinuableScopeManager"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder.visit(
                        Advice.to(ContinuableScopeManagerAdvice.ScheduleRootIterationCleanup.class)
                            .on(
                                isMethod()
                                    .and(named("scheduleRootIterationScopeCleanup"))
                                    .and(takesArguments(2))
                                    .and(
                                        takesArgument(
                                            0, named("datadog.trace.core.scopemanager.ScopeStack")))
                                    .and(
                                        takesArgument(
                                            1,
                                            named(
                                                "datadog.trace.core.scopemanager.ContinuableScope")))
                                    .and(returns(void.class)))))
            .installOn(instrumentation);
  }
}
