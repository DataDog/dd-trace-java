package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Bracket {@code Thread.sleep} call sites at class-load time so a {@code datadog.TaskBlock} JFR
 * event covers the sleep interval.
 *
 * <p>Why caller-site rewriting rather than {@code @Advice} on {@code Thread.sleep} itself: the
 * native profiler covered {@code Thread.sleep} via JVMTI {@code MonitorWait}/{@code Waited} on JDK
 * &lt; 21, but that path is gated off on JDK 21+ (sharing the {@code can_generate_monitor_events}
 * capability with monitor-contended events). On JDK 21+ the preferred approach is bytecode
 * rewriting at user call sites, which mirrors how the {@code synchronized-contention} module
 * ({@link datadog.trace.instrumentation.synccontention.SynchronizedContentionInstrumentation})
 * closes the same generation's gap for {@code MONITORENTER}.
 *
 * <p>Coverage is purely opt-in by the user's bytecode: any {@code INVOKESTATIC
 * java/lang/Thread.sleep(J)V} or {@code (JI)V} in a non-JDK class is wrapped. Reflection-driven
 * sleeps and JNI-driven sleeps remain uncovered (intentional: out-of-band call paths).
 *
 * <p>Active on every JDK so the {@code datadog.TaskBlock} sleep population is consistent across
 * versions. The native JVMTI monitor callbacks cover {@code Object.wait()} and synchronized
 * contention, not {@code Thread.sleep}, so sleep coverage is provided by this call-site
 * instrumentation.
 */
@AutoService(InstrumenterModule.class)
public class ThreadSleepProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasTypeAdvice {

  public ThreadSleepProfilingInstrumentation() {
    super("thread-sleep");
  }

  @Override
  public boolean isEnabled() {
    // Active on every JDK — see class javadoc for rationale.
    return JavaVirtualMachine.isJavaVersionAtLeast(8) && super.isEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    return "java.lang.Object";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // Match every loaded class — Thread.sleep call sites can appear anywhere. The per-method
    // visitor is a pass-through for methods without Thread.sleep INVOKESTATIC, so cost of
    // inspecting irrelevant classes is bounded.
    //
    // JDK / agent / bytebuddy internals are excluded to avoid bootstrap re-entry and
    // self-instrumentation; in particular Thread.sleep's own callers inside java.lang.* would
    // create a class-load loop because TaskBlockHelper itself sits in agent-bootstrap.
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
    transformer.applyAdvice(new ThreadSleepRewritingVisitor());
  }
}
