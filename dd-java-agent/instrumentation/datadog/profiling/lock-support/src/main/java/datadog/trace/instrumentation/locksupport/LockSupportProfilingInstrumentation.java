// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.locksupport;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.TaskBlockInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.java.concurrent.LockSupportHelper;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Instruments {@link java.util.concurrent.locks.LockSupport} park and unpark operations for
 * TaskBlock profiling. The native hooks own platform-thread park intervals only; virtual-thread
 * calls are rejected by ddprof without touching carrier-thread ownership.
 */
@AutoService(InstrumenterModule.class)
public class LockSupportProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  /** Creates the LockSupport profiling instrumentation module. */
  public LockSupportProfilingInstrumentation() {
    super("lock-support");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && TaskBlockInstrumentationConfig.isEnabled(Config.get(), ConfigProvider.getInstance());
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"java.util.concurrent.locks.LockSupport"};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        parkMethod("park", 1).or(parkMethod("parkUntil", 2)),
        getClass().getName() + "$ParkWithBlockerAdvice");
    transformer.applyAdvice(
        parkMethod("park", 0).or(parkMethod("parkUntil", 1)),
        getClass().getName() + "$ParkWithoutBlockerAdvice");
    transformer.applyAdvice(
        parkMethod("parkNanos", 2), getClass().getName() + "$ParkNanosWithBlockerAdvice");
    transformer.applyAdvice(
        parkMethod("parkNanos", 1), getClass().getName() + "$ParkNanosWithoutBlockerAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("unpark"))
            .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport"))),
        getClass().getName() + "$UnparkAdvice");
  }

  private static ElementMatcher.Junction<MethodDescription> parkMethod(String name, int arguments) {
    return isMethod()
        .and(isStatic())
        .and(named(name))
        .and(takesArguments(arguments))
        .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport")));
  }

  /** Advice for park variants whose first argument is the blocker object. */
  public static final class ParkWithBlockerAdvice {
    /** Starts the paired profiling lifecycle before the park. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before(
        @Advice.Argument(0) Object blocker, @Advice.Local("blockerHash") long blockerHash) {
      ProfilingContextIntegration profiling = LockSupportHelper.parkEnter();
      if (profiling != null) {
        blockerHash = Integer.toUnsignedLong(System.identityHashCode(blocker));
      }
      return profiling;
    }

    /** Completes an accepted profiling lifecycle after the park. */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter ProfilingContextIntegration profiling,
        @Advice.Local("blockerHash") long blockerHash) {
      LockSupportHelper.parkExit(profiling, blockerHash);
    }
  }

  /** Advice for park variants without an explicit blocker object. */
  public static final class ParkWithoutBlockerAdvice {
    /** Starts the paired profiling lifecycle before the park. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before() {
      return LockSupportHelper.parkEnter();
    }

    /** Completes an accepted profiling lifecycle after the park. */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter ProfilingContextIntegration profiling) {
      LockSupportHelper.parkExit(profiling, 0L);
    }
  }

  /** Advice for timed park variants whose first argument is the blocker object. */
  public static final class ParkNanosWithBlockerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before(
        @Advice.Argument(0) Object blocker,
        @Advice.Argument(1) long nanos,
        @Advice.Local("blockerHash") long blockerHash) {
      if (nanos <= 0) {
        return null;
      }
      ProfilingContextIntegration profiling = LockSupportHelper.parkEnter();
      if (profiling != null) {
        blockerHash = Integer.toUnsignedLong(System.identityHashCode(blocker));
      }
      return profiling;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter ProfilingContextIntegration profiling,
        @Advice.Local("blockerHash") long blockerHash) {
      LockSupportHelper.parkExit(profiling, blockerHash);
    }
  }

  /** Advice for timed park variants without an explicit blocker object. */
  public static final class ParkNanosWithoutBlockerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before(@Advice.Argument(0) long nanos) {
      return nanos > 0 ? LockSupportHelper.parkEnter() : null;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter ProfilingContextIntegration profiling) {
      LockSupportHelper.parkExit(profiling, 0L);
    }
  }

  /** Advice that records the active span of the latest unpark caller. */
  public static final class UnparkAdvice {
    /** Updates best-effort unpark attribution before dispatching to the JDK. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before(@Advice.Argument(0) Thread thread) {
      LockSupportHelper.recordUnpark(thread);
    }
  }
}
