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
import datadog.trace.agent.tooling.WeakMaps;
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
 * TaskBlock profiling. The native park hooks own platform-thread park intervals only. A virtual
 * thread is timed with an advice-local {@code nanoTime} (it survives unmount and carrier migration
 * in the frozen frame) and reported on exit, only when the virtual-park sub-flag is on.
 */
@AutoService(InstrumenterModule.class)
public class LockSupportProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForBootstrap, Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final String VIRTUAL_THREAD = "java.lang.VirtualThread";

  /** Creates the LockSupport profiling instrumentation module. */
  public LockSupportProfilingInstrumentation() {
    // This is part of wall-clock profiling, not a separately configurable tracing integration.
    super("wallclock");
  }

  @Override
  public boolean isEnabled() {
    if (!super.isEnabled()
        || !TaskBlockInstrumentationConfig.isEnabled(Config.get(), ConfigProvider.getInstance())) {
      return false;
    }
    // LockSupportHelper keeps unpark attribution in a WeakMap, but the agent only registers the
    // WeakMap supplier when per-store context maps are enabled.
    WeakMaps.registerAsSupplier();
    return true;
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {"java.util.concurrent.locks.LockSupport", VIRTUAL_THREAD};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(parkMethod("park", 1), getClass().getName() + "$ParkWithBlockerAdvice");
    transformer.applyAdvice(
        parkMethod("park", 0), getClass().getName() + "$ParkWithoutBlockerAdvice");
    transformer.applyAdvice(
        parkMethod("parkNanos", 2), getClass().getName() + "$ParkNanosWithBlockerAdvice");
    transformer.applyAdvice(
        parkMethod("parkNanos", 1), getClass().getName() + "$ParkNanosWithoutBlockerAdvice");
    transformer.applyAdvice(
        parkMethod("parkUntil", 2), getClass().getName() + "$ParkUntilWithBlockerAdvice");
    transformer.applyAdvice(
        parkMethod("parkUntil", 1), getClass().getName() + "$ParkUntilWithoutBlockerAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("unpark"))
            .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport"))),
        getClass().getName() + "$UnparkAdvice");
    // Absent before JDK 21, where nothing matches. Without the sub-flag nothing is rewritten.
    if (TaskBlockInstrumentationConfig.isVirtualParkEnabled(
        Config.get(), ConfigProvider.getInstance())) {
      transformer.applyAdvice(
          isMethod().and(named("parkOnCarrierThread")).and(isDeclaredBy(named(VIRTUAL_THREAD))),
          getClass().getName() + "$PinnedParkAdvice");
    }
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
        @Advice.Argument(0) Object blocker,
        @Advice.Local("blockerHash") long blockerHash,
        @Advice.Local("virtualStart") long virtualStart) {
      virtualStart = LockSupportHelper.virtualParkStart();
      if (virtualStart != 0L) {
        return null;
      }
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
        @Advice.Argument(0) Object blocker,
        @Advice.Local("blockerHash") long blockerHash,
        @Advice.Local("virtualStart") long virtualStart) {
      LockSupportHelper.parkExit(profiling, blockerHash, virtualStart, blocker);
    }
  }

  /** Advice for park variants without an explicit blocker object. */
  public static final class ParkWithoutBlockerAdvice {
    /** Starts the paired profiling lifecycle before the park. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before(
        @Advice.Local("virtualStart") long virtualStart) {
      virtualStart = LockSupportHelper.virtualParkStart();
      return virtualStart != 0L ? null : LockSupportHelper.parkEnter();
    }

    /** Completes an accepted profiling lifecycle after the park. */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter ProfilingContextIntegration profiling,
        @Advice.Local("virtualStart") long virtualStart) {
      LockSupportHelper.parkExit(profiling, 0L, virtualStart, null);
    }
  }

  /** Advice for timed park variants whose first argument is the blocker object. */
  public static final class ParkNanosWithBlockerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before(
        @Advice.Argument(0) Object blocker,
        @Advice.Argument(1) long nanos,
        @Advice.Local("blockerHash") long blockerHash,
        @Advice.Local("virtualStart") long virtualStart) {
      if (nanos <= 0) {
        return null;
      }
      virtualStart = LockSupportHelper.virtualParkStart();
      if (virtualStart != 0L) {
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
        @Advice.Argument(0) Object blocker,
        @Advice.Local("blockerHash") long blockerHash,
        @Advice.Local("virtualStart") long virtualStart) {
      LockSupportHelper.parkExit(profiling, blockerHash, virtualStart, blocker);
    }
  }

  /** Advice for timed park variants without an explicit blocker object. */
  public static final class ParkNanosWithoutBlockerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before(
        @Advice.Argument(0) long nanos, @Advice.Local("virtualStart") long virtualStart) {
      if (nanos <= 0) {
        return null;
      }
      virtualStart = LockSupportHelper.virtualParkStart();
      return virtualStart != 0L ? null : LockSupportHelper.parkEnter();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter ProfilingContextIntegration profiling,
        @Advice.Local("virtualStart") long virtualStart) {
      LockSupportHelper.parkExit(profiling, 0L, virtualStart, null);
    }
  }

  /** Advice for absolute-deadline park variants whose first argument is the blocker object. */
  public static final class ParkUntilWithBlockerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before(
        @Advice.Argument(0) Object blocker,
        @Advice.Argument(1) long deadline,
        @Advice.Local("blockerHash") long blockerHash,
        @Advice.Local("virtualStart") long virtualStart) {
      if (deadline <= System.currentTimeMillis()) {
        return null;
      }
      virtualStart = LockSupportHelper.virtualParkStart();
      if (virtualStart != 0L) {
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
        @Advice.Argument(0) Object blocker,
        @Advice.Local("blockerHash") long blockerHash,
        @Advice.Local("virtualStart") long virtualStart) {
      LockSupportHelper.parkExit(profiling, blockerHash, virtualStart, blocker);
    }
  }

  /** Advice for absolute-deadline park variants without an explicit blocker object. */
  public static final class ParkUntilWithoutBlockerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ProfilingContextIntegration before(
        @Advice.Argument(0) long deadline, @Advice.Local("virtualStart") long virtualStart) {
      if (deadline <= System.currentTimeMillis()) {
        return null;
      }
      virtualStart = LockSupportHelper.virtualParkStart();
      return virtualStart != 0L ? null : LockSupportHelper.parkEnter();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Enter ProfilingContextIntegration profiling,
        @Advice.Local("virtualStart") long virtualStart) {
      LockSupportHelper.parkExit(profiling, 0L, virtualStart, null);
    }
  }

  /**
   * Marks the current virtual thread as parking on its carrier. {@code VirtualThread.park*} calls
   * {@code parkOnCarrierThread} only when it could not yield, so the carrier stays blocked with the
   * virtual thread mounted: the wall-clock samples already cover that interval.
   */
  public static final class PinnedParkAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void before() {
      LockSupportHelper.markPinnedPark();
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
