// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.TaskBlockInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Bracket {@code Thread.sleep} call sites at class-load time so a {@code datadog.TaskBlock} JFR
 * event covers the sleep interval.
 *
 * <p>Why caller-site rewriting rather than {@code @Advice} on {@code Thread.sleep} itself: the JDK
 * implements sleep independently of {@code Object.wait()}, so JVMTI {@code MonitorWait}/{@code
 * MonitorWaited} callbacks do not bracket it. The native wallprecheck OS-thread-state filter can
 * suppress {@code SIGVTALRM} for sleeping threads (when {@code wallprecheck=true}), but it does not
 * emit a {@code datadog.TaskBlock} event. Rewriting application call sites provides the missing
 * interval without transforming {@code java.lang.Thread}.
 *
 * <p>Coverage is purely opt-in by the user's bytecode: any supported {@code Thread.sleep(...)} or
 * {@code TimeUnit.sleep(long)} call site in a non-JDK class is wrapped. Reflection-driven sleeps
 * and JNI-driven sleeps remain uncovered (intentional: out-of-band call paths).
 *
 * <p>Active on every JDK when enabled via {@code profiling.ddprof.wall.precheck=true} (opt-in;
 * default is off). The native JVMTI monitor callbacks cover {@code Object.wait()} and synchronized
 * contention but not {@code Thread.sleep}, so sleep coverage is provided exclusively by this
 * call-site instrumentation. The helper synchronously brackets an eligible platform-thread sleep
 * with native TaskBlock ownership. Native entry rejects traced and virtual threads, so Java does
 * not retain span or carrier-thread state.
 *
 * <p><b>Performance note:</b> matched classes are scanned cheaply first. The {@link
 * net.bytebuddy.asm.AsmVisitorWrapper} and its {@code COMPUTE_FRAMES} cost are only attached when a
 * supported sleep call site is found, or when scanning fails open.
 */
@AutoService(InstrumenterModule.class)
public class ThreadSleepProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasTypeAdvice {

  public ThreadSleepProfilingInstrumentation() {
    super("thread-sleep");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && TaskBlockInstrumentationConfig.isEnabled(Config.get(), ConfigProvider.getInstance());
  }

  @Override
  public String hierarchyMarkerType() {
    // null = no specific marker type; match broadly across all user-loaded classes.
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // Match every loaded class - sleep call sites can appear anywhere. The per-method
    // visitor is a pass-through for methods without supported sleep calls, so cost of
    // inspecting irrelevant classes is bounded.
    //
    // JDK / agent / bytebuddy internals are excluded to avoid bootstrap re-entry and
    // self-instrumentation; in particular Thread.sleep's own callers inside java.lang.* would
    // create a class-load loop because TaskBlockHelper itself sits in agent-bootstrap.
    //
    // JDK-generated dynamic proxy classes do not contain Thread.sleep INVOKESTATIC call sites, so
    // retaining jdk.proxy* would add class-load overhead with zero coverage benefit.
    return not(
        nameStartsWith("java.")
            .or(nameStartsWith("javax."))
            .or(nameStartsWith("jdk."))
            .or(nameStartsWith("sun."))
            .or(nameStartsWith("com.sun."))
            .or(nameStartsWith("datadog."))
            .or(nameStartsWith("net.bytebuddy.")));
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(
        (builder, typeDescription, classLoader, module, pd) ->
            ThreadSleepScanner.containsThreadSleepCallSite(classLoader, typeDescription)
                ? builder.visit(new ThreadSleepRewritingVisitor())
                : builder);
  }
}
