// Copyright 2026 Datadog, Inc.
package datadog.trace.instrumentation.threadsleep;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.AdviceType.AROUND;
import static net.bytebuddy.matcher.ElementMatchers.not;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.csi.Advices;
import datadog.trace.agent.tooling.bytebuddy.csi.CallSiteTransformer;
import datadog.trace.agent.tooling.csi.CallSites;
import datadog.trace.api.Config;
import datadog.trace.api.profiling.TaskBlockInstrumentationConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Bracket {@code Thread.sleep} call sites at class-load time so a {@code datadog.TaskBlock} JFR
 * event covers the sleep interval.
 *
 * <p>Why caller-site rewriting rather than {@code @Advice} on {@code Thread.sleep} itself: the JDK
 * implements sleep independently of {@code Object.wait()}, so JVMTI {@code MonitorWait}/{@code
 * MonitorWaited} callbacks do not bracket it. The native wallprecheck OS-thread-state filter can
 * suppress {@code SIGVTALRM} for sleeping threads (when {@code wallprecheck=true}), but it does not
 * emit a {@code datadog.TaskBlock} event. Rewriting application call sites provides the missing
 * interval without transforming {@code java.lang.Thread}.
 *
 * <p>Coverage is purely opt-in by the user's bytecode: exact {@code Thread.sleep(...)} and {@code
 * TimeUnit.sleep(long)} call sites in non-JDK classes are wrapped. Calls expressed through a {@code
 * Thread} subclass, reflection, or JNI remain uncovered intentionally. Exact-owner matching avoids
 * mistaking a subclass's hidden static {@code sleep} method for {@code Thread.sleep}.
 *
 * <p>Active on every JDK when enabled via {@code profiling.ddprof.wall.precheck=true} (opt-in;
 * default is off). The native JVMTI monitor callbacks cover {@code Object.wait()} and synchronized
 * contention but not {@code Thread.sleep}, so sleep coverage is provided exclusively by this
 * call-site instrumentation. The helper synchronously brackets an eligible platform-thread sleep
 * with native TaskBlock ownership. Native entry rejects traced and virtual threads, so Java does
 * not retain span or carrier-thread state.
 *
 * <p><b>Performance note:</b> the call-site transformer filters on the actual class-file constant
 * pool and replaces each supported invocation with a stack-compatible static helper call. It does
 * not reload class resources, add caller-side exception handlers, or recompute stack-map frames.
 */
@AutoService(InstrumenterModule.class)
public class ThreadSleepProfilingInstrumentation extends InstrumenterModule.Profiling
    implements Instrumenter.ForCallSite, Instrumenter.HasTypeAdvice {

  private static final String TASK_BLOCK_HELPER =
      "datadog/trace/bootstrap/instrumentation/java/concurrent/TaskBlockHelper";

  public ThreadSleepProfilingInstrumentation() {
    // This is part of wall-clock profiling, not a separately configurable tracing integration.
    super("wallclock");
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled()
        && TaskBlockInstrumentationConfig.isEnabled(Config.get(), ConfigProvider.getInstance());
  }

  @Override
  public ElementMatcher<TypeDescription> callerType() {
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
    transformer.applyAdvice(new CallSiteTransformer("thread-sleep", createAdvices()));
  }

  static Advices createAdvices() {
    return Advices.fromCallSites(new ThreadSleepCallSites());
  }

  private static final class ThreadSleepCallSites implements CallSites {
    @Override
    public void accept(Container container) {
      container.addAdvice(
          AROUND,
          "java/lang/Thread",
          "sleep",
          "(J)V",
          (handler, opcode, owner, name, descriptor, isInterface) ->
              handler.advice(TASK_BLOCK_HELPER, "sleep", "(J)V"));
      container.addAdvice(
          AROUND,
          "java/lang/Thread",
          "sleep",
          "(JI)V",
          (handler, opcode, owner, name, descriptor, isInterface) ->
              handler.advice(TASK_BLOCK_HELPER, "sleep", "(JI)V"));
      container.addAdvice(
          AROUND,
          "java/lang/Thread",
          "sleep",
          "(Ljava/time/Duration;)V",
          (handler, opcode, owner, name, descriptor, isInterface) ->
              handler.advice(TASK_BLOCK_HELPER, "sleepDuration", "(Ljava/lang/Object;)V"));
      container.addAdvice(
          AROUND,
          "java/util/concurrent/TimeUnit",
          "sleep",
          "(J)V",
          (handler, opcode, owner, name, descriptor, isInterface) ->
              handler.advice(TASK_BLOCK_HELPER, "sleep", "(Ljava/util/concurrent/TimeUnit;J)V"));
    }
  }
}
