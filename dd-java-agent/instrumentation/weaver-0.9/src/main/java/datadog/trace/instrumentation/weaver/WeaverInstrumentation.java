package datadog.trace.instrumentation.weaver;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import net.bytebuddy.asm.Advice;
import sbt.testing.TaskDef;
import weaver.framework.SuiteEvent;

@AutoService(InstrumenterModule.class)
public class WeaverInstrumentation extends InstrumenterModule.CiVisibility
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public WeaverInstrumentation() {
    super("ci-visibility", "weaver");
  }

  @Override
  public String instrumentedType() {
    return "weaver.framework.SbtTask";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".WeaverUtils",
      packageName + ".DatadogWeaverReporter",
      packageName + ".TaskDefAwareLinkedBlockingQueueProxy",
      packageName + ".TaskDefAwareConcurrentLinkedQueueProxy",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    // disneystreaming/weaver-test (0.8.4+) uses a ConcurrentLinkedQueue
    transformer.applyAdvice(
        isConstructor().and(takesArgument(5, named("java.util.concurrent.ConcurrentLinkedQueue"))),
        WeaverInstrumentation.class.getName() + "$ConcurrentLinkedQueueAdvice");
    // typelevel/weaver-test (0.9+) uses a LinkedBlockingQueue
    transformer.applyAdvice(
        isConstructor().and(takesArgument(5, named("java.util.concurrent.LinkedBlockingQueue"))),
        WeaverInstrumentation.class.getName() + "$LinkedBlockingQueueAdvice");
  }

  public static class ConcurrentLinkedQueueAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapQueue(
        @Advice.Argument(0) TaskDef taskDef,
        @Advice.Argument(value = 5, readOnly = false) ConcurrentLinkedQueue<SuiteEvent> queue) {
      if (!(queue instanceof TaskDefAwareConcurrentLinkedQueueProxy)) {
        queue = new TaskDefAwareConcurrentLinkedQueueProxy<>(taskDef, queue);
      }
    }
  }

  public static class LinkedBlockingQueueAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void wrapQueue(
        @Advice.Argument(0) TaskDef taskDef,
        @Advice.Argument(value = 5, readOnly = false) LinkedBlockingQueue<SuiteEvent> queue) {
      if (!(queue instanceof TaskDefAwareLinkedBlockingQueueProxy)) {
        queue = new TaskDefAwareLinkedBlockingQueueProxy<>(taskDef, queue);
      }
    }
  }
}
