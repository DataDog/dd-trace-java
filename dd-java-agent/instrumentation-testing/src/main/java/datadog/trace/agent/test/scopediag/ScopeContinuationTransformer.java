package datadog.trace.agent.test.scopediag;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

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
    if (transformer == null) {
      install(
          ByteBuddyAgent.getInstrumentation(),
          "datadog.trace.core",
          ClassFileLocator.ForClassLoader.of(ScopeContinuationTransformer.class.getClassLoader()));
    }
  }

  static synchronized void install(
      Instrumentation instrumentation, String corePackage, ClassFileLocator locator) {
    if (transformer != null) {
      return;
    }
    try {
      // Related core types can otherwise load these targets reentrantly while they are transformed.
      ClassLoader loader = ScopeContinuationTransformer.class.getClassLoader();
      for (String target :
          new String[] {
            ".scopemanager.ScopeContinuation",
            ".scopemanager.ScopeStack",
            ".scopemanager.ContinuableScope",
            ".scopemanager.ContinuableScopeManager",
            ".PendingTrace"
          }) {
        Class.forName(corePackage + target, false, loader);
      }
    } catch (ClassNotFoundException missingCoreTracer) {
      throw new IllegalStateException(
          "Scope continuation diagnostics require dd-trace-core", missingCoreTracer);
    }
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Set<String> transformed = Collections.synchronizedSet(new HashSet<String>());
    transformer =
        new AgentBuilder.Default()
            .with(
                new AgentBuilder.Listener.Adapter() {
                  @Override
                  public void onTransformation(
                      TypeDescription type,
                      ClassLoader loader,
                      JavaModule module,
                      boolean loaded,
                      DynamicType dynamicType) {
                    transformed.add(type.getName());
                  }

                  @Override
                  public void onError(
                      String typeName,
                      ClassLoader loader,
                      JavaModule module,
                      boolean loaded,
                      Throwable error) {
                    failure.compareAndSet(
                        null, new IllegalStateException("Cannot instrument " + typeName, error));
                  }
                })
            .disableClassFormatChanges()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .type(named(corePackage + ".scopemanager.ScopeContinuation"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder
                        .visit(
                            Advice.to(ContinuationAdvice.Register.class, locator)
                                .on(
                                    isMethod()
                                        .and(named("register"))
                                        .and(takesArguments(0))
                                        .and(
                                            returns(
                                                named(
                                                    corePackage
                                                        + ".scopemanager.ScopeContinuation")))))
                        .visit(
                            Advice.to(ContinuationAdvice.Activate.class, locator)
                                .on(
                                    isMethod()
                                        .and(named("resume"))
                                        .and(takesArguments(0))
                                        .and(returns(named("datadog.context.ContextScope")))))
                        .visit(
                            Advice.to(ContinuationAdvice.Cancel.class, locator)
                                .on(
                                    isMethod()
                                        .and(
                                            named("release")
                                                .or(named("cancelFromContinuedScopeClose")))
                                        .and(takesArguments(0))
                                        .and(returns(void.class)))))
            .type(named(corePackage + ".PendingTrace"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder.visit(
                        Advice.to(PendingTraceAdvice.Write.class, locator)
                            .on(
                                isMethod()
                                    .and(named("write"))
                                    .and(takesArguments(boolean.class))
                                    .and(returns(int.class)))))
            .type(named(corePackage + ".scopemanager.ContinuableScope"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder
                        .visit(
                            Advice.to(ContinuableScopeAdvice.OnProperClose.class, locator)
                                .on(
                                    isMethod()
                                        .and(named("onProperClose"))
                                        .and(takesArguments(0))
                                        .and(returns(void.class))))
                        .visit(
                            Advice.to(ContinuableScopeAdvice.Close.class, locator)
                                .on(
                                    isMethod()
                                        .and(named("close"))
                                        .and(takesArguments(0))
                                        .and(returns(void.class)))))
            .type(named(corePackage + ".scopemanager.ScopeStack"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder.visit(
                        Advice.to(ScopeStackAdvice.Push.class, locator)
                            .on(
                                isMethod()
                                    .and(named("push"))
                                    .and(takesArguments(1))
                                    .and(
                                        takesArgument(
                                            0,
                                            named(corePackage + ".scopemanager.ContinuableScope")))
                                    .and(returns(void.class)))))
            .type(named(corePackage + ".scopemanager.ContinuableScopeManager"))
            .transform(
                (builder, type, classLoader, module, pd) ->
                    builder.visit(
                        Advice.to(
                                ContinuableScopeManagerAdvice.ScheduleRootIterationCleanup.class,
                                locator)
                            .on(
                                isMethod()
                                    .and(named("scheduleRootIterationScopeCleanup"))
                                    .and(takesArguments(2))
                                    .and(
                                        takesArgument(
                                            0, named(corePackage + ".scopemanager.ScopeStack")))
                                    .and(
                                        takesArgument(
                                            1,
                                            named(corePackage + ".scopemanager.ContinuableScope")))
                                    .and(returns(void.class)))))
            .installOn(instrumentation);
    if (failure.get() != null || transformed.size() != 5) {
      transformer.reset(instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
      transformer = null;
      throw new IllegalStateException(
          "Scope diagnostics installation failed; transformed " + transformed, failure.get());
    }
  }
}
