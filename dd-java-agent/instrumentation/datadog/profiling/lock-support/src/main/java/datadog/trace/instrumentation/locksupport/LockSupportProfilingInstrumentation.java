package datadog.trace.instrumentation.locksupport;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.java.concurrent.LockSupportHelper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
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
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public LockSupportProfilingInstrumentation() {
    super("lock-support");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"java.util.concurrent.locks.LockSupport"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        parkMethod().and(takesArgument(0, named("java.lang.Object"))),
        getClass().getName() + "$ParkWithBlockerAdvice");
    transformer.applyAdvice(
        parkMethod().and(ElementMatchers.not(takesArgument(0, named("java.lang.Object")))),
        getClass().getName() + "$ParkWithoutBlockerAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(ElementMatchers.named("unpark"))
            .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport"))),
        getClass().getName() + "$UnparkAdvice");
  }

  private static ElementMatcher.Junction<MethodDescription> parkMethod() {
    return isMethod()
        .and(isStatic())
        .and(nameStartsWith("park"))
        .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport")));
  }

  public static final class ParkWithBlockerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static LockSupportHelper.ParkState before(@Advice.Argument(0) Object blocker) {
      return LockSupportHelper.captureState(blocker);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter LockSupportHelper.ParkState state) {
      LockSupportHelper.finish(state);
    }
  }

  public static final class ParkWithoutBlockerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static LockSupportHelper.ParkState before() {
      return LockSupportHelper.captureState(null);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter LockSupportHelper.ParkState state) {
      LockSupportHelper.finish(state);
    }
  }

  public static final class UnparkAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.Argument(0) Thread thread) {
      LockSupportHelper.recordUnpark(thread);
    }
  }
}
