package datadog.trace.instrumentation.synccontention;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments JVM {@code MONITORENTER} opcodes and {@code ACC_SYNCHRONIZED} methods at class-load
 * time to emit {@code datadog.TaskBlock} JFR events on entry-queue contention.
 *
 * <p>Companion to {@code object-wait} (instruments {@link Object#wait(long)}) and {@code
 * lock-support} (instruments {@code LockSupport.park*}). Together these three modules close the gap
 * on JDK 21+ where the native JVMTI {@code MonitorContendedEnter}/{@code Entered} callbacks are
 * gated off (requesting {@code can_generate_monitor_events} triggers per-monitor bytecode
 * instrumentation cost on HotSpot &ge; 21).
 *
 * <p>Unlike its siblings, {@code synchronized} has no Java method boundary &mdash; the JVM acquires
 * the lock implicitly via the {@code MONITORENTER} opcode (for {@code synchronized(obj)} blocks) or
 * via the {@code ACC_SYNCHRONIZED} method flag. Therefore this module uses an {@link
 * net.bytebuddy.asm.AsmVisitorWrapper} that rewrites class bytecode rather than {@code @Advice}
 * method-boundary hooks.
 *
 * <p>{@code unblockingSpanId} is always 0: we cannot identify the lock-releasing thread from the
 * contended thread's vantage point in bytecode. Matches {@code WaitAdvice} in {@code object-wait}.
 */
@AutoService(InstrumenterModule.class)
public class SynchronizedContentionInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasTypeAdvice {

  public SynchronizedContentionInstrumentation() {
    super("synchronized-contention");
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(21) && super.isEnabled();
  }

  @Override
  public String hierarchyMarkerType() {
    // null = no specific marker type; match broadly across all user-loaded classes.
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // Match every loaded class (broad) to mirror the native JVMTI monitor-contended coverage on
    // JDK<21. The per-method visitor is a pass-through for methods with neither MONITORENTER nor
    // ACC_SYNCHRONIZED, so cost of inspecting irrelevant classes is bounded.
    //
    // JDK / agent / bytebuddy internals are excluded to avoid bootstrap re-entry, class-load
    // loops and self-instrumentation of the helper itself.
    // Note: jdk.internal.* is excluded but jdk.proxy* is intentionally NOT excluded so that
    // reflective proxies containing synchronized blocks are covered.
    return not(
        nameStartsWith("java.")
            .or(nameStartsWith("javax."))
            .or(nameStartsWith("jdk.internal."))
            .or(nameStartsWith("sun."))
            .or(nameStartsWith("com.sun."))
            .or(nameStartsWith("datadog."))
            .or(nameStartsWith("net.bytebuddy.")));
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new SynchronizedRewritingVisitor());
  }
}
