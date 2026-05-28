package datadog.trace.instrumentation.synccontention;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.profiling.TaskBlockInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments JVM {@code MONITORENTER} opcodes and {@code ACC_SYNCHRONIZED} methods at class-load
 * time to emit {@code datadog.TaskBlock} JFR events on entry-queue contention.
 *
 * <p>Companion to {@code object-wait} (instruments {@link Object#wait(long)}) and {@code
 * lock-support} (instruments {@code LockSupport.park*}). On JDK 21+, object-wait and
 * synchronized-contention TaskBlock ownership is all-or-native: both Java modules must be enabled
 * together, otherwise native JVMTI owns both monitor populations. This avoids double-emitting one
 * Java population while the shared native monitor-events capability is active for both.
 *
 * <p>Unlike its siblings, {@code synchronized} has no Java method boundary &mdash; the JVM acquires
 * the lock implicitly via the {@code MONITORENTER} opcode (for {@code synchronized(obj)} blocks) or
 * via the {@code ACC_SYNCHRONIZED} method flag. Therefore this module uses an {@link
 * net.bytebuddy.asm.AsmVisitorWrapper} that rewrites class bytecode rather than {@code @Advice}
 * method-boundary hooks.
 *
 * <p>{@code unblockingSpanId} is always 0: we cannot identify the lock-releasing thread from the
 * contended thread's vantage point in bytecode. Matches {@code WaitAdvice} in {@code object-wait}.
 *
 * <p><b>Performance note:</b> the {@link net.bytebuddy.asm.AsmVisitorWrapper} is applied to every
 * non-JDK class at load time. ByteBuddy's {@code COMPUTE_FRAMES} flag triggers a full bytecode
 * analysis pass (frame re-computation) on each instrumented class. This is more expensive than
 * {@code @Advice} method-boundary hooks, which only touch matched methods. The overhead is
 * proportional to the number of classes loaded. See {@code
 * src/jmh/.../SynchronizedInstrumentationBenchmark} for benchmark results ({@code baseline} vs.
 * {@code computeFrames} vs. {@code withRewrite}).
 */
@AutoService(InstrumenterModule.class)
public class SynchronizedContentionInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasTypeAdvice {

  public SynchronizedContentionInstrumentation() {
    super("synchronized-contention");
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(21)
        && super.isEnabled()
        && Config.get().isDatadogProfilerEnabled()
        && TaskBlockInstrumentationConfig.shouldUseJavaMonitorTaskBlockInstrumentation(
            ConfigProvider.getInstance(), InstrumenterConfig.get());
  }

  @Override
  public String hierarchyMarkerType() {
    // null = no specific marker type; match broadly across all user-loaded classes.
    return null;
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // Match every loaded non-JDK class (broad) for the agent-owned JDK 21+ synchronized-contention
    // population. This is not identical to native JVMTI coverage: excluded JDK/agent/native paths
    // remain native-only when delegation is not active. The per-method visitor is a pass-through
    // for
    // methods with neither MONITORENTER nor ACC_SYNCHRONIZED, so inspection cost is bounded.
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
