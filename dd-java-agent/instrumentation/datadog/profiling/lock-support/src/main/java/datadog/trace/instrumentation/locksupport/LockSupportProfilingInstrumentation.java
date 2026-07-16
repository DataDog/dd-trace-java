// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.locksupport;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.TaskBlockInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
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
        parkMethod().and(takesArgument(0, named("java.lang.Object"))),
        getClass().getName() + "$ParkWithBlockerAdvice");
    transformer.applyAdvice(
        parkMethod().and(not(takesArgument(0, named("java.lang.Object")))),
        getClass().getName() + "$ParkWithoutBlockerAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("unpark"))
            .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport"))),
        getClass().getName() + "$UnparkAdvice");
  }

  private static ElementMatcher.Junction<MethodDescription> parkMethod() {
    return isMethod()
        .and(isStatic())
        .and(nameStartsWith("park"))
        .and(isDeclaredBy(named("java.util.concurrent.locks.LockSupport")));
  }

  /** Advice for park variants whose first argument is the blocker object. */
  public static final class ParkWithBlockerAdvice {
    /** Starts the paired profiling lifecycle before the park. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static LockSupportHelper.ParkState before(@Advice.Argument(0) Object blocker) {
      return LockSupportHelper.captureState(blocker);
    }

    /** Completes an accepted profiling lifecycle after the park. */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter LockSupportHelper.ParkState state) {
      LockSupportHelper.finish(state);
    }
  }

  /** Advice for park variants without an explicit blocker object. */
  public static final class ParkWithoutBlockerAdvice {
    /** Starts the paired profiling lifecycle before the park. */
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static LockSupportHelper.ParkState before() {
      return LockSupportHelper.captureState(null);
    }

    /** Completes an accepted profiling lifecycle after the park. */
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter LockSupportHelper.ParkState state) {
      LockSupportHelper.finish(state);
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
