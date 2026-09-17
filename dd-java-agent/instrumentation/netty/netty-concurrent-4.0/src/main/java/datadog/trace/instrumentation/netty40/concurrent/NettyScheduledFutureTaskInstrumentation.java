package datadog.trace.instrumentation.netty40.concurrent;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public final class NettyScheduledFutureTaskInstrumentation
    extends InstrumenterModule.ContextTracking
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public NettyScheduledFutureTaskInstrumentation() {
    super("netty-concurrent");
  }

  @Override
  public int order() {
    // Apply this advice first so its enter hook sets the marker before the generic enter hook reads
    // it on the same run() invocation.
    return -1;
  }

  @Override
  public String hierarchyMarkerType() {
    return ScheduledFuture.class.getName();
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameEndsWith(".netty.util.concurrent.ScheduledFutureTask")
        .and(implementsInterface(named(ScheduledFuture.class.getName())));
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "java.util.concurrent.ScheduledFuture", Boolean.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isMethod().and(named("run")), getClass().getName() + "$Run");
  }

  public static final class Run {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void markEarlyRun(@Advice.This ScheduledFuture<?> task) {
      // Netty 4.1.44+ invokes run() once to self-enqueue delayed tasks, then again when due.
      if (task.getDelay(TimeUnit.NANOSECONDS) > 0) {
        InstrumentationContext.get(ScheduledFuture.class, Boolean.class).put(task, Boolean.TRUE);
      }
    }
  }
}
