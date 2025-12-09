package datadog.trace.instrumentation.jdbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import java.util.concurrent.SynchronousQueue;
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
      packageName + ".HikariBlockedTracker", packageName + ".HikariBlockedTrackingSynchronousQueue",
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
      handoffQueue = new HikariBlockedTrackingSynchronousQueue<>();
    }
  }
}
