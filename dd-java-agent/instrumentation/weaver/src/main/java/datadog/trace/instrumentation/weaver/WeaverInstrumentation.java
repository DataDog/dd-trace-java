package datadog.trace.instrumentation.weaver;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.util.concurrent.ConcurrentLinkedQueue;
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
      packageName + ".TaskDefAwareQueueProxy",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), WeaverInstrumentation.class.getName() + "$SbtTaskCreationAdvice");
  }

  public static class SbtTaskCreationAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onTaskCreation(
        @Advice.FieldValue(value = "queue", readOnly = false)
            ConcurrentLinkedQueue<SuiteEvent> queue,
        @Advice.FieldValue("taskDef") TaskDef taskDef) {
      queue = new TaskDefAwareQueueProxy<SuiteEvent>(taskDef, queue);
    }
  }
}
