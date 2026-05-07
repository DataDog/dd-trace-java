package datadog.trace.instrumentation.locksupport;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.ConcurrentHashMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * Instruments {@link java.util.concurrent.locks.LockSupport#park} variants as the Java entry point
 * for native parked-state tracking. The native profiler uses this state to suppress wall-clock
 * signals while the thread is parked and, when the interval belongs to an active span, to emit a
 * replacement {@code datadog.TaskBlock} event on {@code parkExit}.
 *
 * <p>Also instruments {@link java.util.concurrent.locks.LockSupport#unpark} to capture the span ID
 * of the unblocking thread, which is then recorded in the native TaskBlock event.
 *
 * <p>{@code parkEnter} runs even without an active span (span id 0) so the native wall-clock
 * precheck can suppress {@code SIGVTALRM} for the whole park interval. TaskBlock JFR emission is
 * gated by the profiler on duration and span context.
 */
@AutoService(InstrumenterModule.class)
public class LockSupportProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public LockSupportProfilingInstrumentation() {
    super("lock-support");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"java.util.concurrent.locks.LockSupport"};
  }

  @Override
  public String[] muzzleIgnoredClassNames() {
    // Static helpers on the advice class produce intra-class references that core-JDK muzzle
    // cannot resolve against an empty application classpath.
    return new String[] {
      getClass().getName() + "$ParkAdvice",
      getClass().getName() + "$State",
      getClass().getName() + "$ParkState"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(nameStartsWith("park"))
            .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport"))),
        getClass().getName() + "$ParkAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(ElementMatchers.named("unpark"))
            .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport"))),
        getClass().getName() + "$UnparkAdvice");
  }

  /** Holds shared state accessible from both {@link ParkAdvice} and {@link UnparkAdvice}. */
  public static final class State {
    /** Maps target thread to the span ID of the thread that called {@code unpark()} on it. */
    public static final ConcurrentHashMap<Thread, Long> UNPARKING_SPAN = new ConcurrentHashMap<>();
  }

  static final class ParkState {
    final ProfilingContextIntegration profiling;
    final long blockerHash;
    final long spanId;
    final long rootSpanId;

    ParkState(
        ProfilingContextIntegration profiling, long blockerHash, long spanId, long rootSpanId) {
      this.profiling = profiling;
      this.blockerHash = blockerHash;
      this.spanId = spanId;
      this.rootSpanId = rootSpanId;
    }
  }

  public static final class ParkAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ParkState before(@Advice.Argument(value = 0, optional = true) Object blocker) {
      return captureState(
          blocker, AgentTracer.get().getProfilingContext(), AgentTracer.activeSpan());
    }

    static ParkState captureState(
        Object blocker, ProfilingContextIntegration profiling, AgentSpan span) {
      if (profiling == null) {
        return null;
      }
      // Always call parkEnter for signal suppression, even without an active span.
      // spanId/rootSpanId = 0 when no active span, and native TaskBlock eligibility filters out
      // zero-span intervals at exit.
      long spanId = 0L;
      long rootSpanId = 0L;
      if (span != null && span.context() instanceof ProfilerContext) {
        ProfilerContext ctx = (ProfilerContext) span.context();
        spanId = ctx.getSpanId();
        rootSpanId = ctx.getRootSpanId();
      }
      profiling.parkEnter(spanId, rootSpanId);
      long blockerHash = blocker != null ? System.identityHashCode(blocker) : 0L;
      return new ParkState(profiling, blockerHash, spanId, rootSpanId);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter ParkState state) {
      // Always drain the map entry before any early return. If we returned first, a stale
      // unblocking-span ID placed by a prior unpark() would persist and be incorrectly
      // attributed to the next TaskBlock event emitted on this thread.
      Long unblockingSpanId = State.UNPARKING_SPAN.remove(Thread.currentThread());
      finish(state, unblockingSpanId != null ? unblockingSpanId : 0L);
    }

    static void finish(ParkState state, long unblockingSpanId) {
      if (state == null) {
        return;
      }
      // parkExit() clears native parked state and records an eligible TaskBlock using the entry
      // tick saved by parkEnter().
      state.profiling.parkExit(state.blockerHash, unblockingSpanId);
    }
  }

  public static final class UnparkAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.Argument(0) Thread thread) {
      if (thread == null) {
        return;
      }
      AgentSpan span = AgentTracer.activeSpan();
      if (span == null || !(span.context() instanceof ProfilerContext)) {
        return;
      }
      ProfilerContext ctx = (ProfilerContext) span.context();
      long effectiveSpanId = ctx.getSpanId();
      State.UNPARKING_SPAN.put(thread, effectiveSpanId);
    }
  }
}
