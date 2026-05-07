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
import datadog.trace.bootstrap.instrumentation.java.concurrent.TaskBlockHelper;
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
    super("object-wait");
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
  public String[] muzzleIgnoredClassNames() {
    // Static helpers on the advice class produce intra-class references that core-JDK muzzle
    // cannot resolve against an empty application classpath.
    return new String[] {getClass().getName() + "$WaitAdvice"};
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

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TaskBlockHelper.State before(@Advice.This Object monitor) {
      return captureState(monitor);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter TaskBlockHelper.State state) {
      finish(state);
    }

    static TaskBlockHelper.State captureState(Object monitor) {
      return TaskBlockHelper.capture(System.identityHashCode(monitor));
    }

    static void finish(TaskBlockHelper.State state) {
      TaskBlockHelper.finish(state);
    }
  }
}
