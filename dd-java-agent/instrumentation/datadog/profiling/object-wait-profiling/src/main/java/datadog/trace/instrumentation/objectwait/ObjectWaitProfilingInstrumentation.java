package datadog.trace.instrumentation.objectwait;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.environment.JavaVirtualMachine;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import net.bytebuddy.asm.Advice;

/**
 * Instruments {@link Object#wait(long)} in JDK 21+ to emit {@code datadog.TaskBlock} JFR events.
 *
 * <p>In JDK 21+, {@code wait(long)} is a pure-Java wrapper around the native {@code wait0(long)},
 * so ByteBuddy can add advice to it. In JDK 8-20 the method is declared {@code native} and is not
 * instrumented by this class (Approach 1 osThreadState precheck already suppresses SIGVTALRM for
 * threads in OBJECT_WAIT state on all JDK versions).
 *
 * <p>Only {@code wait(long)} is instrumented: {@code wait()} delegates to {@code wait(0L)} and
 * {@code wait(long, int)} delegates to {@code wait(long)}, so all wait variants are covered.
 *
 * <p>{@code unblockingSpanId} is always 0 because {@code notify()} and {@code notifyAll()} remain
 * {@code native} in JDK 21+ and the notifying thread cannot be identified via BCI.
 */
@AutoService(InstrumenterModule.class)
public class ObjectWaitProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public ObjectWaitProfilingInstrumentation() {
    super("object-wait-profiling");
  }

  @Override
  public boolean isEnabled() {
    return JavaVirtualMachine.isJavaVersionAtLeast(21) && super.isEnabled();
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"java.lang.Object"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("wait"))
            .and(takesArguments(1))
            .and(takesArgument(0, long.class))
            .and(isDeclaredBy(named("java.lang.Object"))),
        getClass().getName() + "$WaitAdvice");
  }

  public static final class WaitAdvice {

    // 1 ms — matches the default LockSupport park threshold in parkExit0
    static final long MIN_WAIT_NANOS = 1_000_000L;

    // State array indices — package-private for readability in tests
    static final int IDX_BLOCKER = 0;
    static final int IDX_SPAN_ID = 1;
    static final int IDX_ROOT_SPAN_ID = 2;
    static final int IDX_START_TICKS = 3;
    static final int IDX_START_NANOS = 4;

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long[] before(@Advice.This Object monitor) {
      return captureState(
          monitor, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan());
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter long[] state) {
      if (state == null) {
        return;
      }
      ProfilingContextIntegration profiling = AgentTracer.get().getProfilingContext();
      if (profiling == null) {
        return;
      }
      emitIfLongEnough(state, profiling);
    }

    /**
     * Captures wait-entry state. Package-private to allow direct unit-testing without a live agent.
     *
     * @return a 5-element {@code long[]} on success, or {@code null} when the preconditions are not
     *     met (no profiling context, no active span, or span context is not a {@link
     *     ProfilerContext})
     */
    static long[] captureState(
        Object monitor, ProfilingContextIntegration profiling, AgentSpan span) {
      if (profiling == null) {
        return null;
      }
      if (span == null || !(span.context() instanceof ProfilerContext)) {
        return null;
      }
      ProfilerContext ctx = (ProfilerContext) span.context();
      return new long[] {
        System.identityHashCode(monitor), // [IDX_BLOCKER]    monitor identity
        ctx.getSpanId(), // [IDX_SPAN_ID]
        ctx.getRootSpanId(), // [IDX_ROOT_SPAN_ID]
        profiling.getCurrentTicks(), // [IDX_START_TICKS] TSC ticks for event timing
        System.nanoTime() // [IDX_START_NANOS]  wall-clock nanos for duration filter
      };
    }

    /**
     * Emits a TaskBlock event if the elapsed wall time since entry exceeds {@link #MIN_WAIT_NANOS}.
     * Package-private to allow direct unit-testing without a live agent.
     */
    static void emitIfLongEnough(long[] state, ProfilingContextIntegration profiling) {
      if (System.nanoTime() - state[IDX_START_NANOS] < MIN_WAIT_NANOS) {
        return;
      }
      // unblockingSpanId = 0: notify/notifyAll are native in JDK 21+,
      // so the notifying thread cannot be identified via BCI.
      profiling.recordTaskBlock(
          state[IDX_START_TICKS],
          state[IDX_SPAN_ID],
          state[IDX_ROOT_SPAN_ID],
          state[IDX_BLOCKER],
          0L);
    }
  }
}
