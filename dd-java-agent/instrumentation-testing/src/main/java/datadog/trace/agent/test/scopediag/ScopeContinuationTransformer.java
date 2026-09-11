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
 */
final class ScopeContinuationTransformer {
  private static volatile ResettableClassFileTransformer transformer;

  private ScopeContinuationTransformer() {}

  static synchronized void install() {
    if (transformer != null) {
      return;
    }
    try {
      // Related core types can otherwise load this target reentrantly while they are transformed.
      Class.forName(
          "datadog.trace.core.scopemanager.ScopeContinuation",
          false,
          ScopeContinuationTransformer.class.getClassLoader());
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
