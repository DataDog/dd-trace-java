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
 * Instruments {@link java.util.concurrent.locks.LockSupport#park} variants to emit {@code
 * datadog.TaskBlock} JFR events. These events record the span, root-span, and duration of every
 * blocking interval, enabling critical-path analysis across async handoffs.
 *
 * <p>Also instruments {@link java.util.concurrent.locks.LockSupport#unpark} to capture the span ID
 * of the unblocking thread, which is then recorded in the TaskBlock event.
 *
 * <p>Only fires when a Datadog span is active on the calling thread, so there is no overhead on
 * threads that are not part of a traced request.
 */
@AutoService(InstrumenterModule.class)
public class LockSupportProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public LockSupportProfilingInstrumentation() {
    super("lock-support-profiling");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"java.util.concurrent.locks.LockSupport"};
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

  public static final class ParkAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static long[] before(@Advice.Argument(value = 0, optional = true) Object blocker) {
      AgentSpan span = AgentTracer.activeSpan();
      if (!(span instanceof ProfilerContext)) {
        return null;
      }
      ProfilerContext ctx = (ProfilerContext) span;
      ProfilingContextIntegration profiling = AgentTracer.get().getProfilingContext();
      long startTicks = profiling.getCurrentTicks();
      if (startTicks == 0L) {
        // profiler not active
        return null;
      }
      long blockerHash = blocker != null ? System.identityHashCode(blocker) : 0L;
      return new long[] {startTicks, ctx.getSpanId(), ctx.getRootSpanId(), blockerHash};
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter long[] state) {
      if (state == null) {
        return;
      }
      Long unblockingSpanId = State.UNPARKING_SPAN.remove(Thread.currentThread());
      AgentTracer.get()
          .getProfilingContext()
          .recordTaskBlock(
              state[0],
              state[1],
              state[2],
              state[3],
              unblockingSpanId != null ? unblockingSpanId : 0L);
    }
  }

  public static final class UnparkAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.Argument(0) Thread thread) {
      if (thread == null) {
        return;
      }
      AgentSpan span = AgentTracer.activeSpan();
      if (!(span instanceof ProfilerContext)) {
        return;
      }
      State.UNPARKING_SPAN.put(thread, ((ProfilerContext) span).getSpanId());
    }
  }
}
