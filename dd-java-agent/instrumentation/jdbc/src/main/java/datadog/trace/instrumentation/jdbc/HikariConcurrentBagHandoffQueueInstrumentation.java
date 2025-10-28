package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Detect blocking for newer Hikari versions starting with commit f0b3c520c (>=2.6.0) by looking for
 * calls to <code>handoffQueue.poll(timeout, NANOSECONDS)</code>.
 */
@AutoService(InstrumenterModule.class)
public final class HikariConcurrentBagHandoffQueueInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType,
        Instrumenter.HasMethodAdvice,
        Instrumenter.WithTypeStructure {

  public HikariConcurrentBagHandoffQueueInstrumentation() {
    super("jdbc", "hikari");
  }

  @Override
  protected boolean defaultEnabled() {
    return InstrumenterConfig.get().isJdbcPoolWaitingEnabled();
  }

  @Override
  public String instrumentedType() {
    return "com.zaxxer.hikari.util.ConcurrentBag";
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("handoffQueue"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".HikariBlockedTracker",
      packageName
          + ".HikariConcurrentBagHandoffQueueInstrumentation$BlockedTrackingSynchronousQueue",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(),
        HikariConcurrentBagHandoffQueueInstrumentation.class.getName() + "$ConstructorAdvice");
  }

  public static class ConstructorAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(
        @Advice.FieldValue(value = "handoffQueue", readOnly = false)
            SynchronousQueue handoffQueue) {
      handoffQueue = new BlockedTrackingSynchronousQueue<>();
    }
  }

  public static class BlockedTrackingSynchronousQueue<T> extends SynchronousQueue<T> {
    public BlockedTrackingSynchronousQueue() {
      // This assumes the initialization of the SynchronousQueue in ConcurrentBag doesn't change
      super(true);
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
      HikariBlockedTracker.setBlocked();
      return super.poll(timeout, unit);
    }
  }
}
